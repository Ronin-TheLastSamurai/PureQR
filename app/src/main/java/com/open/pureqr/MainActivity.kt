package com.open.pureqr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

sealed class ParsedPayload {
    data class SecureUrl(val url: String) : ParsedPayload()
    data class InsecureUrl(val url: String) : ParsedPayload()
    data class Upi(val payeeName: String?, val upiId: String, val amount: String?, val raw: String) : ParsedPayload()
    data class Wifi(val ssid: String, val password: String?, val authType: String) : ParsedPayload()
    data class PlainText(val text: String) : ParsedPayload()
}

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
    val scope = rememberCoroutineScope()

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<ParsedPayload?>(null) }
    var isScanningActive by remember { mutableStateOf(true) }

    // Pinch-to-zoom transient HUD state
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var isPinching by remember { mutableStateOf(false) }
    var zoomHudJob by remember { mutableStateOf<Job?>(null) }

    // Tap-to-focus ring state
    var focusTapPoint by remember { mutableStateOf<PointF?>(null) }
    val focusRingAlpha = remember { Animatable(0f) }
    val focusRingScale = remember { Animatable(1.5f) }

    // Gallery Picker
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scanFromGalleryUri(context, uri) { result ->
                if (result != null) {
                    isScanningActive = false
                    scanResult = result
                    triggerHapticFeedback(context)
                } else {
                    Toast.makeText(context, "No QR or Barcode detected in image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    cameraInstance?.let { cam ->
                        val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val minRatio = zoomState.minZoomRatio
                        val maxRatio = zoomState.maxZoomRatio

                        val newZoom = (currentZoomRatio * zoomChange).coerceIn(minRatio, minOf(maxRatio, 8.0f))
                        currentZoomRatio = newZoom
                        cam.cameraControl.setZoomRatio(newZoom)

                        isPinching = true
                        zoomHudJob?.cancel()
                        zoomHudJob = scope.launch {
                            delay(1000)
                            isPinching = false
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    previewViewRef?.let { pView ->
                        val factory = pView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                        cameraInstance?.cameraControl?.startFocusAndMetering(action)

                        focusTapPoint = PointF(offset.x, offset.y)
                        scope.launch {
                            focusRingAlpha.snapTo(1f)
                            focusRingScale.snapTo(1.4f)
                            focusRingScale.animateTo(1f, tween(200))
                            delay(600)
                            focusRingAlpha.animateTo(0f, tween(300))
                            focusTapPoint = null
                        }
                    }
                }
            }
    ) {
        // CameraX Live Preview View
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).also { previewViewRef = it }
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

        // Viewfinder Vignette Overlay
        ViewfinderOverlay()

        // Tap-to-Focus Animated Ring
        focusTapPoint?.let { pt ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFFB388FF).copy(alpha = focusRingAlpha.value),
                    radius = 32.dp.toPx() * focusRingScale.value,
                    center = Offset(pt.x, pt.y),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Top Header: Branding Chip + Torch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 44.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cute & Aesthetic In-App Branding Chip
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⛶",
                    color = Color(0xFFB388FF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PureQR",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }

            // Flashlight Toggle
            IconButton(
                onClick = {
                    cameraInstance?.let { cam ->
                        val target = !isTorchEnabled
                        cam.cameraControl.enableTorch(target)
                        isTorchEnabled = target
                    }
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                    .size(44.dp)
            ) {
                Icon(
                    imageVector = if (isTorchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flashlight Toggle",
                    tint = if (isTorchEnabled) Color(0xFFFFD54F) else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Transient Zoom HUD Pill (Appears only during pinch, fades smoothly)
        AnimatedVisibility(
            visible = isPinching,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = String.format("%.1f×", currentZoomRatio),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom Controls: Gallery Picker Button
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(
                onClick = {
                    galleryPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .size(54.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Scan from Gallery",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // Result Confirmation Card
        scanResult?.let { result ->
            ResultCard(
                payload = result,
                onDismiss = {
                    scanResult = null
                    isScanningActive = true
                    cameraInstance?.cameraControl?.setZoomRatio(1.0f)
                    currentZoomRatio = 1.0f
                }
            )
        }
    }
}

@Composable
fun ViewfinderOverlay() {
    val strokeColor = Color(0xFF7C4DFF)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val boxSize = size.width * 0.72f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2.3f

        with(drawContext.canvas.nativeCanvas) {
            val check = saveLayer(null, null)

            // Dim Background Vignette
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                size = size
            )

            // Transparent Center Cutout
            drawRoundRect(
                topLeft = Offset(left, top),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                color = Color.Transparent,
                blendMode = BlendMode.Clear
            )

            restoreToCount(check)
        }

        // Corner Reticle Brackets (Electric Violet)
        val cornerLength = 32.dp.toPx()
        val cornerRadius = 24.dp.toPx()
        val strokeWidth = 4.dp.toPx()

        // Top-Left Corner
        drawLine(strokeColor, Offset(left + cornerRadius, top), Offset(left + cornerRadius + cornerLength, top), strokeWidth)
        drawLine(strokeColor, Offset(left, top + cornerRadius), Offset(left, top + cornerRadius + cornerLength), strokeWidth)
        drawArc(
            color = strokeColor,
            startAngle = 180f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(left, top),
            size = Size(cornerRadius * 2, cornerRadius * 2),
            style = Stroke(width = strokeWidth)
        )

        // Top-Right Corner
        val right = left + boxSize
        drawLine(strokeColor, Offset(right - cornerRadius - cornerLength, top), Offset(right - cornerRadius, top), strokeWidth)
        drawLine(strokeColor, Offset(right, top + cornerRadius), Offset(right, top + cornerRadius + cornerLength), strokeWidth)
        drawArc(
            color = strokeColor,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(right - cornerRadius * 2, top),
            size = Size(cornerRadius * 2, cornerRadius * 2),
            style = Stroke(width = strokeWidth)
        )

        // Bottom-Left Corner
        val bottom = top + boxSize
        drawLine(strokeColor, Offset(left + cornerRadius, bottom), Offset(left + cornerRadius + cornerLength, bottom), strokeWidth)
        drawLine(strokeColor, Offset(left, bottom - cornerRadius - cornerLength), Offset(left, bottom - cornerRadius), strokeWidth)
        drawArc(
            color = strokeColor,
            startAngle = 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(left, bottom - cornerRadius * 2),
            size = Size(cornerRadius * 2, cornerRadius * 2),
            style = Stroke(width = strokeWidth)
        )

        // Bottom-Right Corner
        drawLine(strokeColor, Offset(right - cornerRadius - cornerLength, bottom), Offset(right - cornerRadius, bottom), strokeWidth)
        drawLine(strokeColor, Offset(right, bottom - cornerRadius - cornerLength), Offset(right, bottom - cornerRadius), strokeWidth)
        drawArc(
            color = strokeColor,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(right - cornerRadius * 2, bottom - cornerRadius * 2),
            size = Size(cornerRadius * 2, cornerRadius * 2),
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
fun ResultCard(
    payload: ParsedPayload,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            when (payload) {
                is ParsedPayload.Upi -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Payment, contentDescription = null, tint = Color(0xFF7C4DFF))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("UPI Payment", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (!payload.payeeName.isNullOrBlank()) {
                                Text("Payee: ${payload.payeeName}", fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Text("UPI ID: ${payload.upiId}", style = MaterialTheme.typography.bodyMedium)
                            if (!payload.amount.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Amount: ₹${payload.amount}", fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, payload.raw) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload.raw))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No UPI app found", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                        ) {
                            Text("Pay with UPI")
                        }
                    }
                }

                is ParsedPayload.Wifi -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF7C4DFF))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Wi-Fi Credentials", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Network (SSID): ${payload.ssid}", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val pwd = payload.password ?: "None (Open)"
                                Text(
                                    text = "Password: " + if (showPassword || payload.password == null) pwd else "••••••••",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (payload.password != null) {
                                    IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.size(24.dp)) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle Password"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, payload.password ?: payload.ssid) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy Password")
                        }
                    }
                }

                is ParsedPayload.SecureUrl -> {
                    Text("Website Link", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(payload.url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, payload.url) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = { openBrowser(context, payload.url) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                        ) {
                            Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open")
                        }
                    }
                }

                is ParsedPayload.InsecureUrl -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unsecured Link (HTTP)", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(payload.url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, payload.url) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = { openBrowser(context, payload.url) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Open Anyway")
                        }
                    }
                }

                is ParsedPayload.PlainText -> {
                    Text("Scanned Text", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(payload.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { copyToClipboard(context, payload.text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy")
                        }
                        Button(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, payload.text)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share text via"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
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
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
        ) {
            Text("Grant Permission")
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processBarcodeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    camera: Camera?,
    onDetected: (ParsedPayload) -> Unit
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

                    // Dynamic Distance Auto-Zoom: If the code is small in frame, smoothly zoom in
                    if (ratio < 0.08f) {
                        val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                        val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1.0f
                        if (currentZoom < 3.0f && currentZoom < maxZoom) {
                            camera.cameraControl.setZoomRatio(minOf(currentZoom * 1.5f, 3.0f))
                        }
                    }
                }

                val rawValue = barcode.rawValue ?: barcode.displayValue
                if (!rawValue.isNullOrBlank()) {
                    val parsed = parsePayload(rawValue)
                    onDetected(parsed)
                    break
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

private fun scanFromGalleryUri(
    context: Context,
    uri: Uri,
    onComplete: (ParsedPayload?) -> Unit
) {
    try {
        val inputImage = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient()
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val first = barcodes.firstOrNull()?.rawValue
                if (!first.isNullOrBlank()) {
                    onComplete(parsePayload(first))
                } else {
                    onComplete(null)
                }
            }
            .addOnFailureListener {
                onComplete(null)
            }
    } catch (e: Exception) {
        onComplete(null)
    }
}

private fun parsePayload(raw: String): ParsedPayload {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("upi://pay", ignoreCase = true) -> {
            val uri = Uri.parse(trimmed)
            val payee = uri.getQueryParameter("pn")
            val upiId = uri.getQueryParameter("pa") ?: trimmed
            val amount = uri.getQueryParameter("am")
            ParsedPayload.Upi(payee, upiId, amount, trimmed)
        }
        trimmed.startsWith("WIFI:", ignoreCase = true) -> {
            val parts = trimmed.removePrefix("WIFI:").removeSuffix(";;").split(";")
            var ssid = ""
            var password: String? = null
            var type = "WPA"
            for (p in parts) {
                when {
                    p.startsWith("S:") -> ssid = p.removePrefix("S:")
                    p.startsWith("P:") -> password = p.removePrefix("P:")
                    p.startsWith("T:") -> type = p.removePrefix("T:")
                }
            }
            ParsedPayload.Wifi(ssid, password, type)
        }
        trimmed.startsWith("http://", ignoreCase = true) -> ParsedPayload.InsecureUrl(trimmed)
        trimmed.startsWith("https://", ignoreCase = true) -> ParsedPayload.SecureUrl(trimmed)
        else -> ParsedPayload.PlainText(trimmed)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("PureQR", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun openBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No web browser found", Toast.LENGTH_SHORT).show()
    }
}

private fun triggerHapticFeedback(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(50)
    }
}
