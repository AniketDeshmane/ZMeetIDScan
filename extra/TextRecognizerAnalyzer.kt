package com.example.zoomcodescanner

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern

class TextRecognizerAnalyzer(
    private val context: Context,
    private val onTextRecognized: (String, String) -> Unit
) : ImageAnalysis.Analyzer {
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Patterns to match different Zoom meeting ID formats
    private val meetingIdPatterns = listOf(
        Pattern.compile("Meeting ID:\\s*(\\d+[-\\d]*\\d+)"),       // Meeting ID: 123-456-7890
        Pattern.compile("Meeting ID[:\\s]+(\\d+[-\\d]*\\d+)"),     // Meeting ID: or Meeting ID 123-456-7890
        Pattern.compile("\\b(\\d{3}[ -]?\\d{4}[ -]?\\d{4})\\b"),   // Direct match for 938 5144 6032 with flexible spacing
        Pattern.compile("\\b(\\d{9,11})\\b"),                      // Direct match for 93851446032 (without hyphens)
        Pattern.compile("ID[:\\s]*(\\d+[-\\s\\d]*\\d+)"),          // Shorter variant like "ID: 123-456-7890"
        Pattern.compile("\\b(?:zoom|join).{0,30}?(\\d{3}[ -]?\\d{4}[ -]?\\d{4})") // Find ID near "zoom" or "join" words
    )
    
    // Tag for logging
    private val TAG = "TextRecognizerAnalyzer"
    
    // Track if camera is processing frames
    private var framesProcessed = 0
    private val frameLogInterval = 30 // Log every 30 frames
    
    // Add a debounce mechanism to avoid multiple rapid detections
    private var lastDetectionTime = 0L
    private val detectionCooldown = 3000L // 3 seconds cooldown
    
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        // Increment frame counter and log periodically to confirm camera is working
        framesProcessed++
        if (framesProcessed % frameLogInterval == 0) {
            Log.d(TAG, "Camera is active: processed $framesProcessed frames")
        }
        
        val mediaImage = imageProxy.image
        
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Check if any text was recognized
                    if (visionText.text.isNotEmpty()) {
                        Log.d(TAG, "OCR text detected: ${visionText.text}")
                        Toast.makeText(context, "Text detected!", Toast.LENGTH_SHORT).show()
                        processRecognizedText(visionText.text)
                    } else {
                        Log.d(TAG, "No text detected in the image")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    Toast.makeText(context, "OCR error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            Log.w(TAG, "No image available from camera")
            imageProxy.close()
        }
    }
    
    private fun processRecognizedText(recognizedText: String) {
        // Apply debounce to avoid multiple rapid detections
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDetectionTime < detectionCooldown) {
            return
        }
        
        // Try each pattern to find a meeting ID
        for (pattern in meetingIdPatterns) {
            val matcher = pattern.matcher(recognizedText)
            if (matcher.find()) {
                var meetingId = matcher.group(1)
                if (meetingId != null) {
                    // Clean up the meeting ID by removing spaces
                    meetingId = meetingId.replace(" ", "")
                    Log.d(TAG, "Meeting ID detected: $meetingId")
                    Toast.makeText(context, "Zoom ID found: $meetingId", Toast.LENGTH_LONG).show()
                    
                    // Generate Zoom URL using the helper class
                    val zoomUrl = ZoomUrlGenerator.generateZoomUrl(meetingId)
                    lastDetectionTime = currentTime
                    onTextRecognized(zoomUrl, meetingId)
                    return
                }
            }
        }
        
        // If no meeting ID pattern found, check if the entire text might be a Zoom URL
        if (recognizedText.contains("zoom.us")) {
            Log.d(TAG, "Zoom URL detected: $recognizedText")
            Toast.makeText(context, "Zoom link found!", Toast.LENGTH_LONG).show()
            
            // Try to extract meeting ID from the URL
            val idMatcher = Pattern.compile("j/(\\d+)").matcher(recognizedText)
            val meetingId = if (idMatcher.find()) {
                idMatcher.group(1) ?: "Unknown ID"
            } else {
                "Unknown ID"
            }
            
            lastDetectionTime = currentTime
            onTextRecognized(recognizedText, meetingId)
        } else {
            // No meeting ID or Zoom URL found, log for debugging
            Log.d(TAG, "Recognized text (no meeting ID pattern): ${recognizedText.take(100)}...")
            Toast.makeText(context, "No Zoom ID found in text", Toast.LENGTH_SHORT).show()
        }
    }
}