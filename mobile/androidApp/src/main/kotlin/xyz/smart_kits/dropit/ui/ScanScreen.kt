package xyz.smart_kits.dropit.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.DEFAULT_ENDPOINT
import xyz.smart_kits.dropit.shared.pairingCodeIn
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.sans
import java.util.concurrent.Executors

/** What a frame from the camera holds, read on the phone: the text of a QR code, or null. Nothing is kept. */
internal class QrReader {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    /** `luma`: the brightness plane, `stride` bytes per row */
    fun read(luma: ByteArray, stride: Int, width: Int, height: Int): String? = runCatching {
        val source = PlanarYUVLuminanceSource(luma, stride, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }

    fun read(image: ImageProxy): String? {
        val plane = image.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        return read(bytes, plane.rowStride, image.width, image.height)
    }
}

/**
 * Point the camera at the other device's pairing code. The camera is asked for only here, at the tap; what
 * it sees is read on the phone and thrown away. Only dropit's own codes do anything: a code by itself or
 * dropit's own link. Anything else gets one line and the scan goes on.
 */
@Composable
fun ScanScreen(onCode: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    // Refused: back to typing the code, without another word
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) granted = true else onBack() }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var notOurs by remember { mutableStateOf(0L) }
    var found by remember { mutableStateOf(false) }
    val hosts = remember { (BuildConfig.ENDPOINTS.split(',') + DEFAULT_ENDPOINT).map { it.trimEnd('/') }.distinct() }
    LaunchedEffect(notOurs) { if (notOurs != 0L) { delay(2500); notOurs = 0 } }
    // A dark screen: the status bar's icons turn light for as long as it shows, then go back to what they were
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (ctx as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = bars?.isAppearanceLightStatusBars
        bars?.isAppearanceLightStatusBars = false
        onDispose { if (wasLight != null) bars.isAppearanceLightStatusBars = wasLight }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF14161A))) {
        if (granted) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(Unit) {
                onDispose {
                    runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
                    executor.shutdown()
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val view = PreviewView(c)
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val reader = QrReader()
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { image ->
                            val text = image.use { if (found) null else reader.read(it) }
                            if (text != null) {
                                val code = pairingCodeIn(text, hosts)
                                view.post {
                                    if (found) return@post
                                    if (code != null) { found = true; onCode(code) } else notOurs = System.currentTimeMillis()
                                }
                            }
                        }
                        runCatching {
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(c))
                    view
                },
            )
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkAction(onBack, icon = Icons.Back, name = stringResource(R.string.back), color = Color.White)
                Txt(stringResource(R.string.label_scan), sans(15.sp, FontWeight.SemiBold), Color.White, Modifier.weight(1f).semantics { heading() }, align = TextAlign.Center)
                Spacer(Modifier.size(40.dp))
            }
            Spacer(Modifier.weight(.7f))
            // Four corners to aim with; the whole picture is read, not just what is inside them
            Box(
                Modifier.size(220.dp).drawBehind {
                    val arm = 34.dp.toPx()
                    val w = 3.dp.toPx()
                    for ((cx, cy) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
                        val dx = if (cx == 0f) arm else -arm
                        val dy = if (cy == 0f) arm else -arm
                        drawLine(Color.White, Offset(cx, cy), Offset(cx + dx, cy), w)
                        drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy), w)
                    }
                },
            )
            Txt(stringResource(R.string.scan_hint), sans(14.sp, lineHeight = 21.sp), Color.White.copy(alpha = .92f), Modifier.padding(start = 30.dp, end = 30.dp, top = 18.dp), align = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            if (notOurs != 0L) {
                Txt(
                    stringResource(R.string.scan_not_ours), sans(13.sp, FontWeight.SemiBold), Color(0xFFC8102E),
                    Modifier.padding(bottom = 14.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(2.dp, Color(0xFFC8102E), RoundedCornerShape(6.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                )
            } else if (camera?.cameraInfo?.hasFlashUnit() == true) {
                val name = stringResource(R.string.scan_torch)
                Box(
                    Modifier.padding(bottom = 14.dp).size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = if (torch) .9f else .14f))
                        .clickable(role = Role.Switch) { torch = !torch; camera?.cameraControl?.enableTorch(torch) }.semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) { Glyph(Icons.Torch, if (torch) Color(0xFF15171C) else Color.White) }
            } else {
                Spacer(Modifier.height(62.dp))
            }
            Txt(
                stringResource(R.string.scan_type_instead), sans(14.sp).copy(textDecoration = TextDecoration.Underline), Color.White.copy(alpha = .92f),
                Modifier.clickable(role = Role.Button, onClick = onBack).padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}
