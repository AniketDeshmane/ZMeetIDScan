package com.amd.zmeetidscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.amd.zmeetidscan.ui.theme.ZMeetIDScanTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.CameraControl
import androidx.camera.core.ExposureState
import androidx.compose.ui.draw.rotate
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private val TAG = "MainActivity"
    
    // State for the confirmation dialog
    private var showConfirmationDialog by mutableStateOf(false)
    private var detectedMeetingId by mutableStateOf("")
    private var detectedZoomUrl by mutableStateOf("")

    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Recompose will trigger camera setup
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Check camera permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        setContent {
            ZMeetIDScanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val showAppContent = remember { mutableStateOf(!DomainManager.isFirstRun(this@MainActivity)) }
                    var scannerPaused by remember { mutableStateOf(false) }
                    
                    if (!showAppContent.value) {
                        // Show domain setup dialog for first run
                        DomainSetupDialog.DomainSetupDialogCompose {
                            // Once domain setup is complete, show main content
                            showAppContent.value = true
                        }
                    } else {
                        // State for camera control and exposure
                        var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
                        var exposureState by remember { mutableStateOf<ExposureState?>(null) }

                        // Regular app content
                        ScannerScreen(
                            cameraControl = cameraControl,
                            exposureState = exposureState,
                            onCameraBound = { control, state ->
                                cameraControl = control
                                exposureState = state
                            },
                            scannerPaused = scannerPaused,
                            onQrCodeScanned = { zoomUrl, meetingId ->
                                if (!scannerPaused) {
                                    scannerPaused = true
                                    showConfirmationDialog = true
                                    detectedMeetingId = meetingId
                                    detectedZoomUrl = zoomUrl
                                }
                            }
                        )
                        
                        // Confirmation dialog
                        if (showConfirmationDialog) {
                            ConfirmationDialog(
                                meetingId = detectedMeetingId,
                                onConfirm = {
                                    handleRecognizedText(detectedZoomUrl)
                                    showConfirmationDialog = false
                                    scannerPaused = false
                                },
                                onDismiss = {
                                    showConfirmationDialog = false
                                    scannerPaused = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScannerScreen(
        cameraControl: CameraControl?,
        exposureState: ExposureState?,
        onCameraBound: (CameraControl, ExposureState) -> Unit,
        scannerPaused: Boolean = false,
        onQrCodeScanned: (String, String) -> Unit
    ) {
        var sliderValue by remember { mutableStateOf(0f) }
        val minExposure = exposureState?.exposureCompensationRange?.lower ?: 0
        val maxExposure = exposureState?.exposureCompensationRange?.upper ?: 0
        var lastManualSliderTime by remember { mutableStateOf(0L) }
        val autoAdjustPauseMs = 3000L
        val currentTime = System.currentTimeMillis()

        // Auto exposure adjustment state
        var pendingAutoAdjust by remember { mutableStateOf<Int?>(null) }

        // Update slider position when exposure state changes
        LaunchedEffect(exposureState) {
            exposureState?.let {
                sliderValue = it.exposureCompensationIndex.toFloat()
            }
        }

        // Listen for pending auto-adjust
        LaunchedEffect(pendingAutoAdjust) {
            val idx = pendingAutoAdjust
            if (idx != null && cameraControl != null && exposureState != null) {
                cameraControl.setExposureCompensationIndex(idx)
                sliderValue = idx.toFloat()
                pendingAutoAdjust = null
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview (always behind everything)
            CameraPreviewWithBrightnessFeedback(
                onCameraBound = onCameraBound,
                onQrCodeScanned = { url, meetingId ->
                    if (!scannerPaused) {
                        onQrCodeScanned(url, meetingId)
                    }
                },
                onBrightnessFeedback = { feedback ->
                    if (cameraControl != null && exposureState != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastManualSliderTime > autoAdjustPauseMs) {
                            val currentIdx = exposureState.exposureCompensationIndex
                            when (feedback) {
                                TextRecognizerAnalyzer.BrightnessFeedback.TOO_BRIGHT -> {
                                    if (currentIdx > minExposure) {
                                        pendingAutoAdjust = currentIdx - 1
                                    }
                                }
                                TextRecognizerAnalyzer.BrightnessFeedback.TOO_DARK -> {
                                    if (currentIdx < maxExposure) {
                                        pendingAutoAdjust = currentIdx + 1
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            )
            ScannerOverlayWithFocus()
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Zoom Meeting Scanner",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (exposureState != null && exposureState.isExposureCompensationSupported && minExposure != maxExposure) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xAA000000)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Brightness",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🌙", fontSize = 16.sp)
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { newValue ->
                                        sliderValue = newValue
                                        cameraControl?.setExposureCompensationIndex(newValue.roundToInt())
                                        lastManualSliderTime = System.currentTimeMillis()
                                    },
                                    valueRange = minExposure.toFloat()..maxExposure.toFloat(),
                                    steps = (maxExposure - minExposure - 1).coerceAtLeast(0),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Color(0xFF00A2AD),
                                        inactiveTrackColor = Color(0x99FFFFFF)
                                    )
                                )
                                Text("☀️", fontSize = 16.sp)
                            }
                        }
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1D1D1D)
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 8.dp
                    )
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 16.dp)
                    ) {
                        Spacer(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.Gray, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Scan the Meeting",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Point camera at meeting ID or invitation",
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Spacer(
                            modifier = Modifier
                                .width(120.dp)
                                .height(4.dp)
                                .background(Color.DarkGray, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ScannerOverlayWithFocus() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    
                    // Define a smaller square for the focus area
                    val frameSize = size.width * 0.6f
                    val cornerLength = frameSize / 5
                    val strokeWidth = 8f
                    
                    // Move the box slightly upwards (e.g., 48.dp)
                    val yOffset = 48.dp.toPx() // You can adjust this value as needed
                    val startX = (size.width - frameSize) / 2
                    val startY = (size.height - frameSize) / 2 - yOffset
                    
                    // Create fully opaque overlay with transparent center
                    val overlayColor = Color(0xFF000000)
                    
                    // Draw four rectangles to create a "hole" in the middle
                    // Left rectangle
                    drawRect(
                        color = overlayColor,
                        topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(startX, size.height)
                    )
                    // Top rectangle
                    drawRect(
                        color = overlayColor,
                        topLeft = Offset(startX, 0f),
                        size = androidx.compose.ui.geometry.Size(frameSize, startY)
                    )
                    // Right rectangle
                    drawRect(
                        color = overlayColor,
                        topLeft = Offset(startX + frameSize, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width - startX - frameSize, size.height)
                    )
                    // Bottom rectangle
                    drawRect(
                        color = overlayColor,
                        topLeft = Offset(startX, startY + frameSize),
                        size = androidx.compose.ui.geometry.Size(frameSize, size.height - startY - frameSize)
                    )
                    // Draw colored corner markers
                    drawLine(
                        color = Color(0xFFFF5252),
                        start = Offset(startX, startY),
                        end = Offset(startX + cornerLength, startY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFF5252),
                        start = Offset(startX, startY),
                        end = Offset(startX, startY + cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFFEB3B),
                        start = Offset(startX + frameSize - cornerLength, startY),
                        end = Offset(startX + frameSize, startY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFFEB3B),
                        start = Offset(startX + frameSize, startY),
                        end = Offset(startX + frameSize, startY + cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF2196F3),
                        start = Offset(startX, startY + frameSize - cornerLength),
                        end = Offset(startX, startY + frameSize),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF2196F3),
                        start = Offset(startX, startY + frameSize),
                        end = Offset(startX + cornerLength, startY + frameSize),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF4CAF50),
                        start = Offset(startX + frameSize - cornerLength, startY + frameSize),
                        end = Offset(startX + frameSize, startY + frameSize),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF4CAF50),
                        start = Offset(startX + frameSize, startY + frameSize),
                        end = Offset(startX + frameSize, startY + frameSize - cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
        )
    }

    @Composable
    private fun ConfirmationDialog(
        meetingId: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { 
                Text(
                    text = "Meeting ID Detected",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.onSurface
                )
            },
            text = { 
                Text(
                    text = "Do you want to join Zoom meeting with\nID: $meetingId?",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.onSurface
                )
            },
            confirmButton = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Join Meeting button (primary color)
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        )
                    ) {
                        Text("Join Meeting", fontSize = 16.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Copy URL button (outlined)
                    OutlinedButton(
                        onClick = {
                            val webUrl = ZoomUrlGenerator.generateWebUrl(
                                meetingId = detectedMeetingId,
                                context = context
                            )
                            ClipboardUtil.copyToClipboard(
                                context = context,
                                text = webUrl,
                                label = "Zoom Meeting URL",
                                showToast = true
                            )
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colorScheme.primary,
                            containerColor = colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, colorScheme.primary)
                    ) {
                        Text("Copy URL", fontSize = 16.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Cancel button (text only)
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = colorScheme.onSurface)
                    }
                }
            },
            dismissButton = null,
            containerColor = colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)
        )
    }

    @Composable
    private fun CameraPreviewWithBrightnessFeedback(
        onCameraBound: (CameraControl, ExposureState) -> Unit,
        onQrCodeScanned: (String, String) -> Unit,
        onBrightnessFeedback: (TextRecognizerAnalyzer.BrightnessFeedback) -> Unit
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        ) {
            if (cameraPermissionGranted) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                cameraExecutor,
                                TextRecognizerAnalyzer(context, { url, meetingId ->
                                    onQrCodeScanned(url, meetingId)
                                }, onBrightnessFeedback)
                            )
                        }
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                        onCameraBound(camera.cameraControl, camera.cameraInfo.exposureState)
                    } catch (e: Exception) {
                        Log.e(TAG, "Use case binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        }
    }

    private fun handleRecognizedText(zoomUrl: String) {
        Log.d(TAG, "Handling Zoom URL: $zoomUrl")
        
        try {
            // Try to open the Zoom app directly
            val zoomIntent = Intent(Intent.ACTION_VIEW, Uri.parse(zoomUrl))
            zoomIntent.setPackage("us.zoom.videomeetings") // Specify Zoom package to ensure it opens in the app
            
            // Check if there's an activity that can handle this intent
            if (zoomIntent.resolveActivity(packageManager) != null) {
                startActivity(zoomIntent)
            } else {
                // Fallback to browser URL if Zoom app is not installed
                val meetingId = zoomUrl.substringAfterLast("confno=")
                val webUrl = ZoomUrlGenerator.generateWebUrl(meetingId, this)
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                startActivity(browserIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Zoom URL", e)
            Toast.makeText(
                this,
                "Error opening Zoom: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}