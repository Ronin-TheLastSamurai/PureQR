package com.open.pureqr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

enum class ContentCategory {
    SECURE_URL,
    INSECURE_URL,
    UPI_PAYMENT,
    WIFI_CONFIG,
    PLAIN_TEXT
}

data class ScanResultData(
    val rawValue: String,
    val category: ContentCategory
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                PureQRApp()
            }
        }
    }
}

@Composable
fun PureQRApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        ScannerScreen()
    } else {
        PermissionDeniedScreen {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<ScanResultData?>(null) }
    var isScanningActive by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        .build()
                    val barcodeScanner = BarcodeScanning.getClient(options)

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (!isScanningActive) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        processBarcodeFrame(
                            imageProxy = imageProxy,
                            scanner = barcodeScanner,
                            camera = cameraInstance,
                            onDetected = { result ->
                                if (isScanningActive) {
                                    isScanningActive = false
                                    scanResult = result
                                    triggerHapticFeedback(ctx)
                                }
                            }
                        )
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraInstance = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top Bar: Torch Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, end = 24.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = {
                    cameraInstance?.let { cam ->
                        val target = !isTorchEnabled
                        cam.cameraControl.enableTorch(target)
                        isTorchEnabled = target
                    }
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(48.dp)
            ) {
                Icon(
                    imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flashlight Toggle",
                    tint = if (isTorchEnabled) Color.Yellow else Color.White
                )
            }
        }

        // Bottom Sheet Confirmation Dialog
        scanResult?.let { result ->
            ResultCard(
                result = result,
                onDismiss = {
                    scanResult = null
                    isScanningActive = true
                    // Reset zoom back to 1.0x when rescanning
                    cameraInstance?.cameraControl?.setZoomRatio(1.0f)
                }
            )
        }
    }
}

@Composable
fun ResultCard(
    result: ScanResultData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header with Security Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (result.category) {
                    ContentCategory.INSECURE_URL -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Security Alert",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unsecured Link (HTTP)",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    ContentCategory.SECURE_URL -> {
                        Text(
                            text = "Website Link",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    ContentCategory.UPI_PAYMENT -> {
                        Text(
                            text = "UPI Payment Link",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    ContentCategory.WIFI_CONFIG -> {
                        Text(
                            text = "Wi-Fi Configuration",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    ContentCategory.PLAIN_TEXT -> {
                        Text(
                            text = "Scanned Content",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Raw Content Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = result.rawValue,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Scanned QR", result.rawValue)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy")
                }

                if (result.category == ContentCategory.SECURE_URL ||
                    result.category == ContentCategory.INSECURE_URL ||
                    result.category == ContentCategory.UPI_PAYMENT
                ) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.rawValue))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (result.category == ContentCategory.INSECURE_URL) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Another")
            }
        }
    }
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "PureQR needs camera access to scan QR codes and barcodes locally on your device.",
            color = Color.LightGray,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processBarcodeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    camera: Camera?,
    onDetected: (ScanResultData) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                val boundingBox = barcode.boundingBox
                if (boundingBox != null && camera != null) {
                    val frameArea = imageProxy.width * imageProxy.height
                    val barcodeArea = boundingBox.width() * boundingBox.height()
                    val ratio = barcodeArea.toFloat() / frameArea.toFloat()

                    // Auto-Zoom: If the detected QR is distant (< 8% of frame area), smooth zoom in
                    if (ratio < 0.08f) {
                        val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                        val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1.0f
                        if (currentZoom < 3.0f && currentZoom < maxZoom) {
                            camera.cameraControl.setZoomRatio(minOf(currentZoom * 1.4f, 3.0f))
                        }
                    }
                }

                val rawValue = barcode.rawValue ?: barcode.displayValue
                if (!rawValue.isNullOrBlank()) {
                    val category = classifyContent(rawValue)
                    onDetected(ScanResultData(rawValue, category))
                    break
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

private fun classifyContent(raw: String): ContentCategory {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) -> ContentCategory.INSECURE_URL
        trimmed.startsWith("https://", ignoreCase = true) -> ContentCategory.SECURE_URL
        trimmed.startsWith("upi://pay", ignoreCase = true) -> ContentCategory.UPI_PAYMENT
        trimmed.startsWith("WIFI:", ignoreCase = true) -> ContentCategory.WIFI_CONFIG
        else -> ContentCategory.PLAIN_TEXT
    }
}

private fun triggerHapticFeedback(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(60)
    }
}
