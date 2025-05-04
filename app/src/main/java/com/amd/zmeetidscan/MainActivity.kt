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
                            onQrCodeScanned = { zoomUrl, meetingId ->
                                showConfirmationDialog = true
                                detectedMeetingId = meetingId
                                detectedZoomUrl = zoomUrl
                            }
                        )
                        
                        // Confirmation dialog
                        if (showConfirmationDialog) {
                            ConfirmationDialog(
                                meetingId = detectedMeetingId,
                                onConfirm = {
                                    handleRecognizedText(detectedZoomUrl)
                                    showConfirmationDialog = false
                                },
                                onDismiss = {
                                    showConfirmationDialog = false
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
        onQrCodeScanned: (String, String) -> Unit
    ) {
        // State for the slider value
        var sliderValue by remember { mutableStateOf(0f) }
        
        // Get min and max values from the range safely
        val minExposure = exposureState?.exposureCompensationRange?.lower ?: 0
        val maxExposure = exposureState?.exposureCompensationRange?.upper ?: 0
        
        // Update slider position when exposure state changes
        LaunchedEffect(exposureState) {
            exposureState?.let {
                sliderValue = it.exposureCompensationIndex.toFloat()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview (always behind everything)
            CameraPreview(onCameraBound, onQrCodeScanned)
            
            // Scanner overlay with focus window
            ScannerOverlayWithFocus()
            
            // Main column containing all UI elements in vertical order
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Zoom Meeting Scanner at top
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
                
                // 2. Brightness Slider in middle (above the bottom controls)
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
                                // Moon icon for minimum brightness
                                Text("🌙", fontSize = 16.sp)
                                
                                // Horizontal slider with custom colors for better visibility
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { newValue ->
                                        sliderValue = newValue
                                        cameraControl?.setExposureCompensationIndex(newValue.roundToInt())
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
                                
                                // Sun icon for maximum brightness
                                Text("☀️", fontSize = 16.sp)
                            }
                        }
                    }
                }
                
                // 3. Scan the Meeting section at bottom
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
                        // Handle at the top
                        Spacer(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.Gray, RoundedCornerShape(2.dp))
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Title text
                        Text(
                            text = "Scan the Meeting",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Subtitle text
                        Text(
                            text = "Point camera at meeting ID or invitation",
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        // Bottom handle/indicator
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
        // Overlay that draws the colored corner frame and adds a focus area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    
                    // Define a smaller square for the focus area
                    val frameSize = size.width * 0.6f  // Reduced from 0.8f to 0.6f
                    val cornerLength = frameSize / 5
                    val strokeWidth = 8f
                    
                    // Calculate the centered position
                    val startX = (size.width - frameSize) / 2
                    val startY = (size.height - frameSize) / 2
                    
                    // Create dark overlay with transparent center
                    // Draw four rectangles to create a "hole" in the middle
                    val overlayColor = Color(0x99000000)  // Semi-transparent black
                    
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
                    
                    // Add subtle gradient for smooth transition (4 gradients, one for each side)
                    val gradientWidth = 40f  // Width of gradient transition
                    
                    // Left edge gradient
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(overlayColor, Color.Transparent),
                            startX = startX - gradientWidth,
                            endX = startX
                        ),
                        topLeft = Offset(startX - gradientWidth, startY),
                        size = androidx.compose.ui.geometry.Size(gradientWidth, frameSize)
                    )
                    
                    // Top edge gradient
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(overlayColor, Color.Transparent),
                            startY = startY - gradientWidth,
                            endY = startY
                        ),
                        topLeft = Offset(startX, startY - gradientWidth),
                        size = androidx.compose.ui.geometry.Size(frameSize, gradientWidth)
                    )
                    
                    // Right edge gradient
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, overlayColor),
                            startX = startX + frameSize,
                            endX = startX + frameSize + gradientWidth
                        ),
                        topLeft = Offset(startX + frameSize, startY),
                        size = androidx.compose.ui.geometry.Size(gradientWidth, frameSize)
                    )
                    
                    // Bottom edge gradient
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, overlayColor),
                            startY = startY + frameSize,
                            endY = startY + frameSize + gradientWidth
                        ),
                        topLeft = Offset(startX, startY + frameSize),
                        size = androidx.compose.ui.geometry.Size(frameSize, gradientWidth)
                    )
                    
                    // Top-left corner (red)
                    drawLine(
                        color = Color(0xFFFF5252), // Red
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
                    
                    // Top-right corner (yellow/orange)
                    drawLine(
                        color = Color(0xFFFFB300), // Orange/Yellow
                        start = Offset(startX + frameSize, startY),
                        end = Offset(startX + frameSize - cornerLength, startY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFFB300),
                        start = Offset(startX + frameSize, startY),
                        end = Offset(startX + frameSize, startY + cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    
                    // Bottom-left corner (blue)
                    drawLine(
                        color = Color(0xFF2196F3), // Blue
                        start = Offset(startX, startY + frameSize),
                        end = Offset(startX + cornerLength, startY + frameSize),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF2196F3),
                        start = Offset(startX, startY + frameSize),
                        end = Offset(startX, startY + frameSize - cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    
                    // Bottom-right corner (green)
                    drawLine(
                        color = Color(0xFF4CAF50), // Green
                        start = Offset(startX + frameSize, startY + frameSize),
                        end = Offset(startX + frameSize - cornerLength, startY + frameSize),
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
        
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { 
                Text(
                    text = "Meeting ID Detected",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = { 
                Text(
                    text = "Do you want to join Zoom meeting with\nID: $meetingId?",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Join Meeting button (teal)
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00A2AD) // Teal color from screenshot
                        )
                    ) {
                        Text("Join Meeting", fontSize = 16.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Copy URL button (white with teal outline)
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
                            contentColor = Color(0xFF00A2AD), // Teal text
                            containerColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color(0xFF00A2AD))
                    ) {
                        Text("Copy URL", fontSize = 16.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Cancel button (text only)
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            },
            dismissButton = null,
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)
        )
    }

    @Composable
    private fun CameraPreview(
        onCameraBound: (CameraControl, ExposureState) -> Unit,
        onQrCodeScanned: (String, String) -> Unit
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
                    
                    // Preview use case
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    
                    // Image analysis use case
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                cameraExecutor,
                                TextRecognizerAnalyzer(context) { url, meetingId ->
                                    onQrCodeScanned(url, meetingId)
                                }
                            )
                        }
                    
                    try {
                        // Unbind all use cases before rebinding
                        cameraProvider.unbindAll()
                        
                        // Bind use cases to camera
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                        // Pass camera control and exposure state back up
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