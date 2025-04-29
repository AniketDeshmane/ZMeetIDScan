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
                        // Regular app content
                        ScannerScreen(
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
    private fun ScannerScreen(onQrCodeScanned: (String, String) -> Unit) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview
            CameraPreview(onQrCodeScanned)
            
            // Scan frame overlay with colored corners
            ScannerOverlay()
            
            // Bottom instructions card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
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
                        text = "Scan any Zoom meeting ID",
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

    @Composable
    private fun ScannerOverlay() {
        // Overlay that draws the colored corner frame
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    
                    val frameWidth = size.width * 0.8f
                    val frameHeight = frameWidth // Keep it square
                    val cornerLength = frameWidth / 5
                    val strokeWidth = 8f
                    
                    // Calculate the centered position
                    val startX = (size.width - frameWidth) / 2
                    val startY = (size.height - frameHeight) / 2
                    
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
                        start = Offset(startX + frameWidth, startY),
                        end = Offset(startX + frameWidth - cornerLength, startY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFFB300),
                        start = Offset(startX + frameWidth, startY),
                        end = Offset(startX + frameWidth, startY + cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    
                    // Bottom-left corner (blue)
                    drawLine(
                        color = Color(0xFF2196F3), // Blue
                        start = Offset(startX, startY + frameHeight),
                        end = Offset(startX + cornerLength, startY + frameHeight),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF2196F3),
                        start = Offset(startX, startY + frameHeight),
                        end = Offset(startX, startY + frameHeight - cornerLength),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    
                    // Bottom-right corner (green)
                    drawLine(
                        color = Color(0xFF4CAF50), // Green
                        start = Offset(startX + frameWidth, startY + frameHeight),
                        end = Offset(startX + frameWidth - cornerLength, startY + frameHeight),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF4CAF50),
                        start = Offset(startX + frameWidth, startY + frameHeight),
                        end = Offset(startX + frameWidth, startY + frameHeight - cornerLength),
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
    private fun CameraPreview(onQrCodeScanned: (String, String) -> Unit) {
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
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
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