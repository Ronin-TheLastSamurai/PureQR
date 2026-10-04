package com.open.pureqr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size as AndroidSize
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

// --- DATA STRUCTURES ---

sealed class ParsedPayload {
    data class SecureUrl(val url: String) : ParsedPayload()
    data class InsecureUrl(val url: String) : ParsedPayload()
    data class Upi(val payeeName: String?, val upiId: String, val amount: String?, val raw: String) : ParsedPayload()
    data class Wifi(val ssid: String, val password: String?, val authType: String) : ParsedPayload()
    data class PlainText(val text: String) : ParsedPayload()

    fun rawString(): String = when (this) {
        is SecureUrl -> url
        is InsecureUrl -> url
        is Upi -> raw
        is Wifi -> "WIFI:S:$ssid;P:${password ?: ""};T:$authType;;"
        is PlainText -> text
    }

    fun title(): String = when (this) {
        is SecureUrl -> "Website Link"
        is InsecureUrl -> "Unsecured Link (HTTP)"
        is Upi -> payeeName ?: "UPI Payment"
        is Wifi -> ssid
        is PlainText -> if (text.length > 25) text.take(25) + "..." else text
    }

    fun categoryName(): String = when (this) {
        is SecureUrl -> "URL"
        is InsecureUrl -> "HTTP"
        is Upi -> "UPI"
        is Wifi -> "WIFI"
        is PlainText -> "TEXT"
    }
}

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val raw: String,
    val title: String,
    val categoryName: String,
    val timestamp: Long = System.currentTimeMillis()
)

// --- LOCAL STORAGE MANAGER (100% OFFLINE) ---

object HistoryStorage {
    private const val PREFS = "pureqr_history"
    private const val KEY = "history_records"
    private const val LIMIT = 50

    fun load(context: Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        raw = obj.getString("raw"),
                        title = obj.getString("title"),
                        categoryName = obj.getString("category"),
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun add(context: Context, payload: ParsedPayload) {
        val list = load(context).toMutableList()
        if (list.firstOrNull()?.raw == payload.rawString()) return
        list.add(0, HistoryItem(raw = payload.rawString(), title = payload.title(), categoryName = payload.categoryName()))
        if (list.size > LIMIT) list.removeAt(list.size - 1)
        save(context, list)
    }

    fun delete(context: Context, id: String): List<HistoryItem> {
        val updated = load(context).filter { it.id != id }
        save(context, updated)
        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun save(context: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("raw", item.raw)
            obj.put("title", item.title)
            obj.put("category", item.categoryName)
            obj.put("timestamp", item.timestamp)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

// --- ACTIVITY & MAIN SCREEN ---

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

@OptIn(ExperimentalMaterial3Api::class)
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

    // Bottom Sheets State
    var showHistorySheet by remember { mutableStateOf(false) }
    var showGeneratorSheet by remember { mutableStateOf(false) }

    // Pinch-to-zoom & smooth zoom states
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var isPinching by remember { mutableStateOf(false) }
    var zoomHudJob by remember { mutableStateOf<Job?>(null) }
    var lastAutoZoomTime by remember { mutableLongStateOf(0L) }

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
                    HistoryStorage.add(context, result)
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
        // Live Camera Preview
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
                        .setTargetResolution(AndroidSize(1280, 720))
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
                            currentZoom = currentZoomRatio,
                            lastAutoZoomTime = lastAutoZoomTime,
                            onTriggerSmoothZoom = { target ->
                                lastAutoZoomTime = System.currentTimeMillis()
                                triggerSmoothZoom(cameraInstance, target, scope) { updated ->
                                    currentZoomRatio = updated
                                }
                            },
                            onDetected = { result ->
                                if (isScanningActive) {
                                    isScanningActive = false
                                    scanResult = result
                                    HistoryStorage.add(ctx, result)
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

        // Transient Zoom HUD Pill
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

        // Bottom Controls: Floating Frosted Pill Dock [ 🖼 | 🕒 | ➕ ]
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 38.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    galleryPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Scan from Gallery",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(Color.White.copy(alpha = 0.2f))
            )

            IconButton(
                onClick = { showHistorySheet = true },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Scan History",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(Color.White.copy(alpha = 0.2f))
            )

            IconButton(
                onClick = { showGeneratorSheet = true },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = "Create QR",
                    tint = Color(0xFFB388FF),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 1. Result ModalBottomSheet
        scanResult?.let { result ->
            ModalBottomSheet(
                onDismissRequest = {
                    scanResult = null
                    isScanningActive = true
                    triggerSmoothZoom(cameraInstance, 1.0f, scope) { currentZoomRatio = it }
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF13121D),
                scrimColor = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
            ) {
                ResultSheetContent(
                    payload = result,
                    onDismiss = {
                        scanResult = null
                        isScanningActive = true
                        triggerSmoothZoom(cameraInstance, 1.0f, scope) { currentZoomRatio = it }
                    }
                )
            }
        }

        // 2. History ModalBottomSheet
        if (showHistorySheet) {
            ModalBottomSheet(
                onDismissRequest = { showHistorySheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF13121D),
                scrimColor = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
            ) {
                HistorySheetContent(
                    onDismiss = { showHistorySheet = false },
                    onSelect = { selected ->
                        showHistorySheet = false
                        scanResult = selected
                    }
                )
            }
        }

        // 3. Generator ModalBottomSheet
        if (showGeneratorSheet) {
            ModalBottomSheet(
                onDismissRequest = { showGeneratorSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF13121D),
                scrimColor = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
            ) {
                GeneratorSheetContent(onDismiss = { showGeneratorSheet = false })
            }
        }
    }
}

// --- SMOOTH ZOOM INTERPOLATION ---

private fun triggerSmoothZoom(
    camera: Camera?,
    targetZoom: Float,
    scope: CoroutineScope,
    onZoomUpdated: (Float) -> Unit
) {
    val cam = camera ?: return
    val zoomState = cam.cameraInfo.zoomState.value ?: return
    val currentZoom = zoomState.zoomRatio
    val clampedTarget = targetZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
    if (kotlin.math.abs(currentZoom - clampedTarget) < 0.05f) return

    scope.launch {
        val steps = 12
        val durationMs = 250L
        val stepDelay = durationMs / steps
        for (i in 1..steps) {
            val fraction = i / steps.toFloat()
            val ease = fraction * (2f - fraction)
            val interpolated = currentZoom + (clampedTarget - currentZoom) * ease
            cam.cameraControl.setZoomRatio(interpolated)
            onZoomUpdated(interpolated)
            delay(stepDelay)
        }
    }
}

// --- SHEET CONTENTS ---

@Composable
fun ResultSheetContent(
    payload: ParsedPayload,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .padding(bottom = 24.dp)
    ) {
        when (payload) {
            is ParsedPayload.Upi -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payment, contentDescription = null, tint = Color(0xFFB388FF), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("UPI Payment", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (!payload.payeeName.isNullOrBlank()) {
                            Text("Payee: ${payload.payeeName}", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text("UPI ID: ${payload.upiId}", color = Color.LightGray, fontSize = 14.sp)
                        if (!payload.amount.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Amount: ₹${payload.amount}", fontWeight = FontWeight.Bold, color = Color(0xFF00E676), fontSize = 16.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { copyToClipboard(context, payload.raw) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
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
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFFB388FF), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Wi-Fi Network", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Network (SSID): ${payload.ssid}", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val pwd = payload.password ?: "None (Open Network)"
                            Text(
                                text = "Password: " + if (showPassword || payload.password == null) pwd else "••••••••",
                                color = Color.LightGray,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (payload.password != null) {
                                IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.size(24.dp)) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Password",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { copyToClipboard(context, payload.password ?: payload.ssid) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Password")
                    }
                }
            }

            is ParsedPayload.SecureUrl -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFFB388FF), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Website Link", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(payload.url, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { copyToClipboard(context, payload.url) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
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
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Unsecured Link (HTTP)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(payload.url, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { copyToClipboard(context, payload.url) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TextFields, contentDescription = null, tint = Color(0xFFB388FF), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Scanned Text", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(payload.text, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { copyToClipboard(context, payload.text) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
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

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Scan Another")
        }
    }
}

@Composable
fun HistorySheetContent(
    onDismiss: () -> Unit,
    onSelect: (ParsedPayload) -> Unit
) {
    val context = LocalContext.current
    var historyList by remember { mutableStateOf(HistoryStorage.load(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History (${historyList.size}/50)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            if (historyList.isNotEmpty()) {
                IconButton(
                    onClick = {
                        HistoryStorage.clear(context)
                        historyList = emptyList()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear All",
                        tint = Color(0xFFFF5252)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No scans saved yet", color = Color.Gray, fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyList, key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(parsePayload(item.raw))
                            },
                        color = Color.White.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF7C4DFF).copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = item.categoryName,
                                    color = Color(0xFFB388FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.timestamp)),
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    historyList = HistoryStorage.delete(context, item.id)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GeneratorSheetContent(onDismiss: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    var rawInput by remember { mutableStateOf("") }

    var upiId by remember { mutableStateOf("") }
    var upiName by remember { mutableStateOf("") }
    var upiAmount by remember { mutableStateOf("") }

    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }

    var generatedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Create QR Code", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = Color.White)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0; generatedBitmap = null },
                label = { Text("Link / Text") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF7C4DFF),
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    labelColor = Color.LightGray
                )
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1; generatedBitmap = null },
                label = { Text("UPI") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF7C4DFF),
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    labelColor = Color.LightGray
                )
            )
            FilterChip(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2; generatedBitmap = null },
                label = { Text("Wi-Fi") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF7C4DFF),
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    labelColor = Color.LightGray
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> {
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = { rawInput = it },
                    label = { Text("Enter Website URL or Text") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF7C4DFF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                    )
                )
            }
            1 -> {
                OutlinedTextField(
                    value = upiId,
                    onValueChange = { upiId = it },
                    label = { Text("UPI ID (e.g. name@okhdfc)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = upiName,
                    onValueChange = { upiName = it },
                    label = { Text("Payee Name (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = upiAmount,
                    onValueChange = { upiAmount = it },
                    label = { Text("Amount ₹ (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            }
            2 -> {
                OutlinedTextField(
                    value = wifiSsid,
                    onValueChange = { wifiSsid = it },
                    label = { Text("Wi-Fi Network Name (SSID)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = wifiPassword,
                    onValueChange = { wifiPassword = it },
                    label = { Text("Wi-Fi Password") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val payloadString = when (selectedTab) {
                    0 -> rawInput.trim()
                    1 -> {
                        val base = "upi://pay?pa=${upiId.trim()}"
                        val withName = if (upiName.isNotBlank()) "$base&pn=${Uri.encode(upiName.trim())}" else base
                        if (upiAmount.isNotBlank()) "$withName&am=${upiAmount.trim()}" else withName
                    }
                    2 -> "WIFI:S:${wifiSsid.trim()};T:WPA;P:${wifiPassword.trim()};;"
                    else -> ""
                }
                if (payloadString.isNotBlank()) {
                    generatedBitmap = generateQrBitmap(payloadString)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
        ) {
            Text("Generate QR")
        }

        generatedBitmap?.let { bmp ->
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Generated QR Code",
                    modifier = Modifier.size(200.dp)
                )
            }
        }
    }
}

// --- QR CODE GENERATION ENGINE ---

private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

// --- VIEWFINDER OVERLAY ---

@Composable
fun ViewfinderOverlay() {
    val strokeColor = Color(0xFF7C4DFF)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val boxSize = size.width * 0.72f
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2.3f

        with(drawContext.canvas.nativeCanvas) {
            val check = saveLayer(null, null)

            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                size = size
            )

            drawRoundRect(
                topLeft = Offset(left, top),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                color = Color.Transparent,
                blendMode = BlendMode.Clear
            )

            restoreToCount(check)
        }

        val cornerLength = 32.dp.toPx()
        val cornerRadius = 24.dp.toPx()
        val strokeWidth = 4.dp.toPx()

        // Top-Left
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

        // Top-Right
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

        // Bottom-Left
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

        // Bottom-Right
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

// --- ANALYSIS & PARSING HELPERS ---

@OptIn(ExperimentalGetImage::class)
private fun processBarcodeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    camera: Camera?,
    currentZoom: Float,
    lastAutoZoomTime: Long,
    onTriggerSmoothZoom: (Float) -> Unit,
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

                    val now = System.currentTimeMillis()
                    if (ratio < 0.12f && currentZoom < 1.8f && (now - lastAutoZoomTime > 1500L)) {
                        onTriggerSmoothZoom(2.2f)
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
