package com.pocketnode.ui.lightning

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Camera-based QR code scanner using CameraX + ZXing.
 * No Google Play Services required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (hasCameraPermission) {
            var scanned by remember { mutableStateOf(false) }

            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    if (!scanned) {
                                        val result = decodeQr(imageProxy)
                                        if (result != null) {
                                            scanned = true
                                            imageProxy.close()
                                            // Post to main thread
                                            previewView.post { onResult(result) }
                                            return@setAnalyzer
                                        }
                                    }
                                    imageProxy.close()
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analyzer
                            )
                        } catch (e: Exception) {
                            Log.e("QrScanner", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera permission required to scan QR codes")
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun decodeQr(imageProxy: ImageProxy): String? {
    val image = imageProxy.image ?: return null
    val plane = image.planes[0]
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val source = PlanarYUVLuminanceSource(
        bytes,
        plane.rowStride,
        image.height,
        0, 0,
        image.width,
        image.height,
        false
    )
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        val result = MultiFormatReader().decode(binaryBitmap)
        result.text
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    }
}
