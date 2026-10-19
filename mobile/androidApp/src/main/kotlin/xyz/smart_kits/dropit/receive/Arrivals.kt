package xyz.smart_kits.dropit.receive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import xyz.smart_kits.dropit.App
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.LOCKED_NOTICE
import xyz.smart_kits.dropit.shared.Notice
import xyz.smart_kits.dropit.shared.NoticeAction
import xyz.smart_kits.dropit.ui.MainActivity
import xyz.smart_kits.dropit.ui.sensitiveClip

/**
 * What arrived, drawn as notifications. On the lock screen all it says is "dropit · something new"; the
 * content shows once the phone is unlocked. What each one does is decided by the shared `noticeFor`.
 */
class Arrivals(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    val allowed: Boolean
        get() = nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    fun show(notice: Notice) {
        if (!allowed) return
        val words = context.app.strings
        val title = notice.title ?: notice.summary?.let(words::render) ?: context.getString(R.string.app_name)
        val body = notice.body ?: notice.from?.let(words::render)
        val text = listOfNotNull(body, notice.from?.takeIf { notice.body != null }?.let(words::render)).joinToString("\n").ifEmpty { null }
        val id = notice.id.hashCode()
        val b = NotificationCompat.Builder(context, App.CHANNEL_RECEIVE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text?.lineSequence()?.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, App.CHANNEL_RECEIVE)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle(words.render(LOCKED_NOTICE))
                    .build(),
            )
        // The first action is also what a tap does; with none, a tap opens dropit
        val first = notice.actions.firstOrNull()
        b.setContentIntent(if (notice.opensApp || first == null) openApp() else tapFor(first, notice))
        for (a in notice.actions) b.addAction(0, label(a), tapFor(a, notice))
        nm.notify(id, b.build())
    }

    private fun label(a: NoticeAction) = context.getString(when (a) {
        NoticeAction.COPY -> R.string.copy
        NoticeAction.OPEN -> R.string.open
        NoticeAction.SHARE -> R.string.share
        NoticeAction.DOWNLOAD -> R.string.open
    })

    private fun letterText(notice: Notice): String? = context.app.letters.all.value.firstOrNull { it.id == notice.id }?.text

    private fun tapFor(a: NoticeAction, notice: Notice): PendingIntent {
        val text = letterText(notice)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val code = (notice.id + a.name).hashCode()
        return when {
            // The text itself does not ride in the intent: a long one (up to a megabyte) is over what a PendingIntent can carry, and
            // the notification would not even be posted. The receiver finds it by the letter's id.
            a == NoticeAction.COPY && text != null -> PendingIntent.getBroadcast(
                context, code, Intent(context, ReceiveActions::class.java).setAction(ReceiveActions.COPY).putExtra(ReceiveActions.LETTER, notice.id).putExtra(ReceiveActions.NOTE, notice.id.hashCode()), flags)
            a == NoticeAction.OPEN && text != null && text.length <= SHORT_TEXT -> PendingIntent.getActivity(
                context, code, Intent(Intent.ACTION_VIEW, Uri.parse(text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            a == NoticeAction.SHARE && text != null && text.length <= SHORT_TEXT -> PendingIntent.getActivity(
                context, code,
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
            else -> openApp()
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Copy from a notification: writing the clipboard is allowed in the background, no window needed */
/** Longer than this is not put in an intent for the system to carry (sharing it, opening it): the notification just opens the app */
private const val SHORT_TEXT = 50_000

class ReceiveActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != COPY) return
        val text = context.app.letters.all.value.firstOrNull { it.id == intent.getStringExtra(LETTER) }?.text ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(sensitiveClip(text))
        // What dropit itself put there is not offered back as "new on your clipboard"
        context.app.store.clipSeen = System.currentTimeMillis()
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(NOTE, 0))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val COPY = "xyz.smart_kits.dropit.COPY"
        const val LETTER = "letter"
        const val NOTE = "note"
    }
}
