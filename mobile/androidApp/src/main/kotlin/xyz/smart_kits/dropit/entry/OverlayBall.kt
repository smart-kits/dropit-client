package xyz.smart_kits.dropit.entry

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import kotlin.math.abs

/**
 * The floating button — an EXPERIMENT, off unless the person turns it on, and only in builds that have it ([Overlay.available]; the
 * store build has neither the permission nor this window). One tap sends the clipboard, the same as the tile.
 *
 * Not tried on a phone yet, and not to be called finished until it is: whether the clipboard can be read from here (a window
 * without focus cannot read it, so a tap opens the same one-pixel window the tile does), whether it survives in other apps and
 * after the process is stopped (it has no foreground service of its own), and what each maker's own "floating windows" switch does.
 */
object Overlay {
    enum class State {
        /** This build does not have it */
        UNAVAILABLE,
        OFF,
        /** Switched on, but the person has not allowed drawing over other apps: [permissionIntent] leads to where to do that */
        NEEDS_PERMISSION,
        ON,
    }

    val available: Boolean get() = BuildConfig.OVERLAY

    fun canDraw(context: Context): Boolean = available && Settings.canDrawOverlays(context)

    /** The system page where this app is allowed to draw over others (some makers send it to a page of their own) */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun state(context: Context): State = when {
        !available -> State.UNAVAILABLE
        !context.app.store.overlayBall -> State.OFF
        !Settings.canDrawOverlays(context) -> State.NEEDS_PERMISSION
        else -> State.ON
    }

    /** The switch: remembers the choice, shows or removes the button, and says where that left things (NEEDS_PERMISSION: send the person to [permissionIntent]) */
    fun setEnabled(context: Context, on: Boolean): State {
        if (!available) return State.UNAVAILABLE
        context.app.store.overlayBall = on
        if (on && canDraw(context)) show(context) else hide(context)
        return state(context)
    }

    /** At the app's start, and when coming back from the permission page: the button is there when it is switched on and allowed */
    fun resume(context: Context) {
        if (available && context.app.store.overlayBall && canDraw(context)) show(context)
    }

    // A plain service, not a foreground one: starting it from the background may be refused (then it waits for the app to be opened)
    private fun show(context: Context) { runCatching { context.startService(Intent(context, OverlayBall::class.java)) } }
    private fun hide(context: Context) { runCatching { context.stopService(Intent(context, OverlayBall::class.java)) } }
}

/** The window itself: a small round button the finger can drag, over other apps */
class OverlayBall : Service() {
    private var ball: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Overlay.canDraw(this) || !app.store.overlayBall) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ball == null) ball = draw()
        return START_STICKY
    }

    override fun onDestroy() {
        ball?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        ball = null
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun draw(): View? {
        val dp = resources.displayMetrics.density
        val size = (48 * dp).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_tile)
            setColorFilter(Color.WHITE)
            val pad = (11 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE61B2A4A.toInt()) }
            contentDescription = getString(R.string.ball_label)
        }
        // No focus (the keyboard and the app underneath carry on as they were): so it cannot read the clipboard itself, it opens the window that can
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (200 * dp).toInt() }
        val wm = getSystemService(WindowManager::class.java)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 8 * dp || abs(e.rawY - downY) > 8 * dp) moved = true
                    if (moved) {
                        params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    app.warm()
                    startActivity(Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            true
        }
        return runCatching { wm.addView(view, params); view }.getOrNull()
    }
}
