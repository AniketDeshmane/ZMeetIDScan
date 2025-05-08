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
import java.util.concurrent.atomic.AtomicBoolean

class TextRecognizerAnalyzer(
    private val context: Context,
    private val onTextRecognized: (String, String) -> Unit,
    private val onBrightnessFeedback: ((BrightnessFeedback) -> Unit)? = null
) : ImageAnalysis.Analyzer {
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Optimized pattern for all valid 11-digit Zoom meeting ID formats
    private val meetingIdPattern = Pattern.compile(
        "(?:Meeting ID[:\\s]+)?(\\d{3}[ -]?\\d{4}[ -]?\\d{4})|\\b(\\d{11})\\b",
        Pattern.CASE_INSENSITIVE
    )
    
    // Tag for logging
    private val TAG = "TextRecognizerAnalyzer"
    
    // Track if camera is processing frames
    private var framesProcessed = 0
    private val frameLogInterval = 100
    
    // Prevent overlapping processing
    private val isProcessing = AtomicBoolean(false)
    
    // Reduced debounce time for faster detection response
    private var lastDetectionTime = 0L
    private val detectionCooldown = 500L
    
    // Define the scan region as a percentage of the center of the image
    private val scanRegionPercentageWidth = 0.6f
    private val scanRegionPercentageHeight = 0.6f
    
    enum class BrightnessFeedback { TOO_BRIGHT, TOO_DARK, OK }
    
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        // Skip if already processing another frame
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return
        }
        
        // Get image
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }
        
        // Calculate scan region bounds to match the visible square on screen
        val imageWidth = mediaImage.width
        val imageHeight = mediaImage.height
        
        // Calculate scan region - this must match the UI square with colored corners
        val centerX = imageWidth / 2
        val centerY = imageHeight / 2
        val scanWidth = (imageWidth * scanRegionPercentageWidth).toInt()
        val scanHeight = (imageHeight * scanRegionPercentageHeight).toInt()
        
        // Define the scan rectangle coordinates
        val scanRect = Rect(
            centerX - (scanWidth / 2),
            centerY - (scanHeight / 2),
            centerX + (scanWidth / 2),
            centerY + (scanHeight / 2)
        )
        
        // Log the scan area dimensions periodically
        if (framesProcessed % frameLogInterval == 0) {
            Log.d(TAG, "Camera active: frames=$framesProcessed, scan area: $scanRect")
        }
        framesProcessed++
        
        try {
            // Process the whole image but filter results by scan area
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    // Filter text elements to only include those inside scan area
                    val filteredText = StringBuilder()
                    var foundTextInside = false
                    
                    for (block in visionText.textBlocks) {
                        val boundingBox = block.boundingBox
                        
                        // If block has a valid bounding box
                        if (boundingBox != null) {
                            // Must be at least 70% inside the scan area to count
                            if (isSignificantlyInsideScanArea(boundingBox, scanRect, 0.7f)) {
                                filteredText.append(block.text).append("\n")
                                foundTextInside = true
                                Log.d(TAG, "Text INSIDE scan area: ${block.text}")
                            } else if (framesProcessed % frameLogInterval == 0) {
                                // Log rejected text periodically
                                Log.d(TAG, "Text OUTSIDE scan area (rejected): ${block.text}")
                            }
                        }
                    }
                    
                    // Only process if we found text inside the scan area
                    val textToProcess = filteredText.toString()
                    if (foundTextInside && textToProcess.isNotEmpty()) {
                        processText(textToProcess)
                        onBrightnessFeedback?.invoke(BrightnessFeedback.OK)
                    } else {
                        // If no text found, estimate brightness
                        val brightness = estimateBrightness(mediaImage)
                        when {
                            brightness > 180 -> onBrightnessFeedback?.invoke(BrightnessFeedback.TOO_BRIGHT)
                            brightness < 60 -> onBrightnessFeedback?.invoke(BrightnessFeedback.TOO_DARK)
                            else -> onBrightnessFeedback?.invoke(BrightnessFeedback.OK)
                        }
                    }
                    
                    isProcessing.set(false)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed: ${e.message}")
                    isProcessing.set(false)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in analyze: ${e.message}")
            isProcessing.set(false)
            imageProxy.close()
        }
    }
    
    // Check if bounding box is significantly inside scan area (at least requiredOverlap percentage)
    private fun isSignificantlyInsideScanArea(boundingBox: Rect, scanRect: Rect, requiredOverlap: Float): Boolean {
        // Create intersection rectangle
        val intersection = Rect(boundingBox)
        if (!intersection.intersect(scanRect)) {
            return false  // No intersection
        }
        
        // Calculate areas
        val intersectionArea = intersection.width() * intersection.height().toFloat()
        val blockArea = boundingBox.width() * boundingBox.height().toFloat()
        
        // Calculate percentage of the block that's inside the scan area
        val overlapPercentage = intersectionArea / blockArea
        
        // More lenient - accept even partial overlaps (30% is enough)
        return overlapPercentage >= 0.30f
    }

    // Process text to find meeting IDs - optimized for speed and reliability
    private fun processText(text: String) {
        // Check debounce - apply minimal delay to avoid UI thrashing
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDetectionTime < detectionCooldown) {
            return
        }
        
        // Log the actual text being processed for debugging
        Log.d(TAG, "Processing text for meeting ID: $text")
        
        // First try exact pattern matching
        val matcher = meetingIdPattern.matcher(text)
        
        // Track if we found a valid ID
        var foundValidId = false
        
        while (matcher.find()) {
            // Get the matched ID from whichever group matched
            val rawMeetingId = matcher.group(1) ?: matcher.group(2)
            
            if (rawMeetingId != null) {
                // Clean the meeting ID by removing all non-digits
                val cleanMeetingId = rawMeetingId.replace(Regex("[^0-9]"), "")
                
                Log.d(TAG, "Potential meeting ID found: $cleanMeetingId (length: ${cleanMeetingId.length})")
                
                // Only trigger for valid 11-digit meeting IDs
                if (cleanMeetingId.length == 11) {
                    Log.d(TAG, "Valid 11-digit Meeting ID detected: $cleanMeetingId")
                    foundValidId = true
                    
                    // Generate URL and validate it's not empty
                    val zoomUrl = ZoomUrlGenerator.generateZoomUrl(cleanMeetingId)
                    if (zoomUrl.isNotEmpty()) {
                        // Update debounce timestamp and trigger callback
                        lastDetectionTime = currentTime
                        onTextRecognized(zoomUrl, formatMeetingId(cleanMeetingId))
                        return
                    }
                }
            }
        }
        
        // If no ID found with the pattern, try a fallback approach with numeric sequences
        if (!foundValidId) {
            // Find all numeric sequences that could be meeting IDs
            val numericSequences = Regex("\\d+").findAll(text)
            for (sequence in numericSequences) {
                val digits = sequence.value
                if (digits.length == 11) {
                    Log.d(TAG, "Fallback: Found 11-digit sequence: $digits")
                    
                    // Generate URL and validate it's not empty
                    val zoomUrl = ZoomUrlGenerator.generateZoomUrl(digits)
                    if (zoomUrl.isNotEmpty()) {
                        // Update debounce timestamp and trigger callback
                        lastDetectionTime = currentTime
                        onTextRecognized(zoomUrl, formatMeetingId(digits))
                        return
                    }
                }
            }
        }
    }
    
    // Format the meeting ID with hyphens for display (XXX-XXXX-XXXX)
    private fun formatMeetingId(digits: String): String {
        if (digits.length != 11) return digits
        return "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
    }

    // Estimate average brightness from the Y plane (luminance)
    private fun estimateBrightness(mediaImage: android.media.Image): Int {
        val yBuffer = mediaImage.planes[0].buffer
        var sum = 0L
        var count = 0
        while (yBuffer.hasRemaining()) {
            sum += (yBuffer.get().toInt() and 0xFF)
            count++
        }
        return if (count > 0) (sum / count).toInt() else 128
    }
}