package fr.luba.poc.gateway

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.PlanarYUVLuminanceSource
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a gateway pairing QR code with this phone's camera (shown on the gateway phone). [onPairing] receives the first
 * valid DumbyLuba pairing; any other QR code is ignored with a hint. Camera permission requested on opening.
 */
@Composable
fun QrScanDialog(onPairing: (GatewayPairing) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; refused = !ok }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    LubaDialog(onDismissRequest = onDismiss, title = tr("Scan the gateway QR code", "Scanner le QR code de la passerelle"), content = {
        InfoNote(tr("On the gateway phone, open the app: its home screen shows the QR code. Aim at it with this phone.",
            "Sur le téléphone passerelle, ouvrez l'application : son écran d'accueil affiche le QR code. Visez-le avec ce téléphone."))
        when {
            granted -> CameraQrReader(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.material3.MaterialTheme.shapes.medium)) { text ->
                val pairing = GatewayPairing.parse(text)
                if (pairing != null) { onPairing(pairing); true } else { other = true; false }
            }
            refused -> StatusNote(tr("Camera refused: allow it in the Android settings, or enter the code by hand.",
                "Caméra refusée : autorisez-la dans les paramètres Android, ou saisissez le code à la main."), NoteTone.WARNING)
            else -> LubaProgress(Modifier.fillMaxWidth())
        }
        if (other) StatusNote(tr("This QR code is not a DumbyLuba gateway pairing.", "Ce QR code n'est pas un appairage de passerelle DumbyLuba."), NoteTone.WARNING)
    }, footer = {
        SecondaryPillButton(tr("Enter the code by hand", "Saisir le code à la main"), onDismiss, Modifier.fillMaxWidth())
    })
}

/**
 * Camera preview analysed for QR codes. [onText] returns true once the text is accepted: analysis then stops.
 * Luminance (Y plane) only, at most one frame being decoded at a time.
 */
@Composable
private fun CameraQrReader(modifier: Modifier, onText: (String) -> Boolean) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose { runCatching { provider?.unbindAll() }; executor.shutdown() }
    }
    AndroidView(factory = { viewContext ->
        PreviewView(viewContext).also { view ->
            val future = ProcessCameraProvider.getInstance(viewContext)
            future.addListener({
                val cameras = runCatching { future.get() }.getOrNull() ?: return@addListener
                provider = cameras
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (done.get()) return@setAnalyzer
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                        val text = QrMatrix.decode(source) ?: return@setAnalyzer
                        main.execute { if (!done.get() && latest(text)) { done.set(true); runCatching { cameras.unbindAll() } } }
                    } finally { image.close() }
                }
                runCatching {
                    cameras.unbindAll()
                    cameras.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, main)
        }
    }, modifier = modifier)
}
