package com.amd.zmeetidscan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

object ClipboardUtil {
    /**
     * Copies text to clipboard and displays a toast message to the user
     * 
     * @param context The application context
     * @param text Text to be copied to clipboard
     * @param label Label for the clip data (defaults to "Zoom URL")
     * @param showToast Whether to show a toast notification (defaults to true)
     * @return Boolean indicating if copy operation was successful
     */
    fun copyToClipboard(
        context: Context, 
        text: String, 
        label: String = "Zoom URL",
        showToast: Boolean = true
    ): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            
            if (clipboard == null) {
                if (showToast) Toast.makeText(context, "Clipboard service unavailable", Toast.LENGTH_SHORT).show()
                return false
            }
            
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            
            // Show toast notification on Android 12 (API 31) and below
            // Android 13+ shows system notification automatically
            if (showToast && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            if (showToast) Toast.makeText(context, "Failed to copy: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * Gets text from clipboard if available
     * 
     * @param context The application context
     * @return The clipboard text or null if not available
     */
    fun getTextFromClipboard(context: Context): String? {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
                
            // Check if clipboard has text
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount!! > 0) {
                val item = clipboard.primaryClip?.getItemAt(0)
                return item?.text?.toString()
            }
        } catch (e: Exception) {
            // Handle exception silently
        }
        return null
    }
}