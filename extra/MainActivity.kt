package com.example.zoomcodescanner

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.zoomcodescanner.ui.theme.ZoomCodeScannerTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.compose.runtime.rememberUpdatedState

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
            ZoomCodeScannerTheme {
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
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreview(
                                onQrCodeScanned = { zoomUrl, meetingId ->
                                    showConfirmationDialog = true
                                    detectedMeetingId = meetingId
                                    detectedZoomUrl = zoomUrl
                                }
                            )
                            
                            // Scanning indicator at the top
                            Text(
                                text = "Scanning for Zoom meeting ID...",
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x99000000))
                                    .padding(16.dp)
                                    .align(Alignment.TopCenter),
                                textAlign = TextAlign.Center
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
            title = { Text("Meeting ID Detected") },
            text = { Text("Do you want to join Zoom meeting with ID: $meetingId?") },
            confirmButton = {
                Column {
                    Button(onClick = onConfirm) {
                        Text("Join Meeting")
                    }
                    Button(
                        onClick = {
                            val webUrl = ZoomUrlGenerator.generateWebUrl(
                                meetingId = detectedZoomUrl.substringAfterLast("confno="),
                                context = context
                            )
                            ClipboardUtil.copyToClipboard(
                                context = context,
                                text = webUrl,
                                label = "Zoom Meeting URL",
                                showToast = true
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Copy URL")
                    }
                }
            },
            dismissButton = {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
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