package dev.haljk.glow.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import dev.haljk.glow.ui.theme.glow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Camera preview that decodes a pairing QR.
 *
 * Frames are analysed on a single background thread and decoding stops at the first hit:
 * a QR reader that keeps calling back fires dozens of times a second and would open a
 * dozen dialogs. The caller gets the raw text and decides what it means.
 *
 * Permission is requested when this composable appears and nowhere else — the app never
 * asks for the camera at launch.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    onBlocked: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (!ok) onBlocked("没有相机权限。可以手动填写地址，或到系统设置里允许后再试。")
    }

    LaunchedEffect(granted, asked) {
        if (!granted && !asked) {
            asked = true
            request.launch(Manifest.permission.CAMERA)
        }
    }

    if (!granted) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("需要相机权限才能扫码", style = MaterialTheme.typography.titleMedium)
            Text(
                "扫一次就能把地址、端口和模式填好；不想开相机的话，关掉这里手动填写也一样能用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { QRCodeReader() }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            delivered = true
            executor.shutdownNow()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(320.dp),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image: ImageProxy ->
                        try {
                            if (!delivered) {
                                decodeQr(image, reader)?.let { text ->
                                    delivered = true
                                    previewView.post { onScanned(text) }
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { onBlocked("相机打不开：" + (it.message ?: "未知原因")) }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.glow.scrim.copy(alpha = 0.55f))
                .padding(12.dp),
        ) {
            Text(
                "把另一台设备上的二维码放进画面",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.glow.onScrim,
            )
        }
    }
}

/** Decode one camera frame as a QR code, or null when it holds none. */
private fun decodeQr(image: ImageProxy, reader: QRCodeReader): String? = runCatching {
    val data = image.planes.first().buffer.let { buffer ->
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        bytes
    }
    val source = PlanarYUVLuminanceSource(
        data,
        image.width,
        image.height,
        0,
        0,
        image.width,
        image.height,
        false,
    )
    reader.decode(BinaryBitmap(HybridBinarizer(source))).text
}.getOrNull()
