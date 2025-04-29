package com.amd.zmeetidscan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
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
    
    // Patterns to match different Zoom meeting ID formats - preferring complete IDs
    private val meetingIdPatterns = listOf(
        // Complete meeting IDs (9-11 digits, with or without hyphens)
        Pattern.compile("\\b(\\d{3}[ -]?\\d{4}[ -]?\\d{4})\\b"),   // Direct match for 938 5144 6032 with flexible spacing
        Pattern.compile("\\b(\\d{9,11})\\b"),                      // Direct match for 93851446032 (without hyphens)
        Pattern.compile("Meeting ID:\\s*(\\d{3}[ -]?\\d{4}[ -]?\\d{4})"), // Meeting ID: 123-456-7890
        Pattern.compile("Meeting ID[:\\s]+(\\d{3}[ -]?\\d{4}[ -]?\\d{4})"), // Meeting ID: or Meeting ID 123-456-7890
        Pattern.compile("ID[:\\s]*(\\d{3}[ -]?\\d{4}[ -]?\\d{4})"), // Shorter variant like "ID: 123-456-7890"
        Pattern.compile("\\b(?:zoom|join).{0,30}?(\\d{3}[ -]?\\d{4}[ -]?\\d{4})"), // Find ID near "zoom" or "join" words
        
        // Partial IDs (fall back only if complete ones aren't found)
        Pattern.compile("\\b(\\d{6,8})\\b"),                       // Partial match for longer numbers that might be IDs
        Pattern.compile("Meeting ID:\\s*(\\d{3,}[-\\d]*\\d+)"),    // Meeting ID: with at least 3+ digits
        Pattern.compile("Meeting ID[:\\s]+(\\d{3,}[-\\d]*\\d+)")   // Meeting ID with at least 3+ digits
    )
    
    // Tag for logging
    private val TAG = "TextRecognizerAnalyzer"
    
    // Track if camera is processing frames
    private var framesProcessed = 0
    private val frameLogInterval = 30 // Log every 30 frames
    
    // Add a debounce mechanism to avoid multiple rapid detections
    private var lastDetectionTime = 0L
    private val detectionCooldown = 2000L // 2 seconds cooldown
    
    // Define the scan region as a percentage of the center of the image
    private val scanRegionPercentageWidth = 0.65f  // Narrower detection area (was 0.8f)
    private val scanRegionPercentageHeight = 0.65f // Make area more square-like
    
    // Keep track of partial IDs we've seen to avoid showing too many false positives
    private val recentPartialIds = mutableSetOf<String>()
    
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
            
            // Calculate the scan region (center area of the image)
            val imageWidth = mediaImage.width
            val imageHeight = mediaImage.height
            
            // Calculate scan region bounds to match the colored frame shown to user
            val centerX = imageWidth / 2
            val centerY = imageHeight / 2
            
            val scanWidth = (imageWidth * scanRegionPercentageWidth).toInt()
            val scanHeight = (imageHeight * scanRegionPercentageHeight).toInt()
            
            val left = centerX - (scanWidth / 2)
            val top = centerY - (scanHeight / 2)
            val right = centerX + (scanWidth / 2)
            val bottom = centerY + (scanHeight / 2)
            
            val scanRect = Rect(left, top, right, bottom)
            
            // Debug logs to understand the scan area dimensions
            if (framesProcessed % 100 == 0) {
                Log.d(TAG, "Scan area: Left=$left, Top=$top, Right=$right, Bottom=$bottom")
                Log.d(TAG, "Image size: Width=$imageWidth, Height=$imageHeight")
            }
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Use a more strict filtering approach for text elements
                    val filteredText = StringBuilder()
                    
                    for (textBlock in visionText.textBlocks) {
                        // Skip blocks without bounding boxes
                        val boundingBox = textBlock.boundingBox ?: continue
                        
                        // Only include text blocks that are COMPLETELY within scan area
                        // This is a more strict check than before
                        if (scanRect.contains(boundingBox)) {
                            filteredText.append(textBlock.text).append("\n")
                        } else {
                            // Debug log for blocks we're rejecting
                            if (framesProcessed % 100 == 0) {
                                Log.d(TAG, "Rejected text outside scan area: ${textBlock.text}")
                            }
                        }
                    }
                    
                    // Process only the filtered text
                    val text = filteredText.toString()
                    if (text.isNotEmpty()) {
                        Log.d(TAG, "OCR text detected INSIDE scan region: ${text.take(100)}...")
                        processRecognizedText(text)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
    
    // Check if a bounding box is completely inside our scan rectangle
    // This is a stricter check than the Android Rect.contains() method
    private fun isCompletelyInsideScanArea(boundingBox: Rect, scanRect: Rect): Boolean {
        return (boundingBox.left >= scanRect.left &&
                boundingBox.top >= scanRect.top &&
                boundingBox.right <= scanRect.right &&
                boundingBox.bottom <= scanRect.bottom)
    }
    
    private fun processRecognizedText(recognizedText: String) {
        // Apply debounce to avoid multiple rapid detections
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDetectionTime < detectionCooldown) {
            return
        }
        
        // First try to find a complete meeting ID (9-11 digits)
        for (i in 0 until 6) { // First 6 patterns are for complete IDs
            val pattern = meetingIdPatterns[i]
            val matcher = pattern.matcher(recognizedText)
            
            if (matcher.find()) {
                var meetingId = matcher.group(1)
                if (meetingId != null) {
                    // Clean up the meeting ID by removing spaces and check length
                    meetingId = meetingId.replace(" ", "").replace("-", "")
                    
                    // Only process if it looks like a complete ID (9-11 digits)
                    if (meetingId.length >= 9 && meetingId.length <= 11) {
                        Log.d(TAG, "Complete Meeting ID detected: $meetingId")
                        lastDetectionTime = currentTime
                        val zoomUrl = ZoomUrlGenerator.generateZoomUrl(meetingId)
                        onTextRecognized(zoomUrl, meetingId)
                        return
                    }
                }
            }
        }
        
        // If Zoom URL is found directly
        if (recognizedText.contains("zoom.us")) {
            Log.d(TAG, "Zoom URL detected: $recognizedText")
            
            // Try to extract meeting ID from the URL
            val idMatcher = Pattern.compile("j/(\\d+)").matcher(recognizedText)
            val meetingId = if (idMatcher.find()) {
                idMatcher.group(1) ?: "Unknown ID"
            } else {
                "Unknown ID"
            }
            
            lastDetectionTime = currentTime
            onTextRecognized(recognizedText, meetingId)
            return
        }
        
        // Only fall back to partial IDs as a last resort
        // Check for partial meeting IDs (less strict patterns)
        for (i in 6 until meetingIdPatterns.size) {
            val pattern = meetingIdPatterns[i]
            val matcher = pattern.matcher(recognizedText)
            
            if (matcher.find()) {
                var meetingId = matcher.group(1)
                if (meetingId != null) {
                    meetingId = meetingId.replace(" ", "").replace("-", "")
                    
                    // Only show partial IDs that are at least 3 digits
                    if (meetingId.length >= 3) {
                        // Don't show the same partial ID again
                        if (!recentPartialIds.contains(meetingId)) {
                            recentPartialIds.add(meetingId)
                            // Limit the size of our tracking set
                            if (recentPartialIds.size > 10) {
                                recentPartialIds.clear()
                            }
                            
                            Log.d(TAG, "Partial Meeting ID detected: $meetingId")
                            Log.d(TAG, "Continuing to scan for complete ID...")
                            // Don't trigger UI for partial IDs, keep scanning
                            // (Note that we intentionally don't update lastDetectionTime here)
                            
                            // Only show partial IDs in a toast, don't trigger the dialog
                            Toast.makeText(context, "Scanning... found: $meetingId", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}