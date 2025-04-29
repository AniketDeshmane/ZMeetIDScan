package com.example.zoomcodescanner

import android.content.Context

/**
 * Utility class to generate correctly formatted Zoom URLs from meeting IDs
 */
object ZoomUrlGenerator {
    // Default domain if user hasn't specified one
    private const val DEFAULT_DOMAIN = "zoom.us"
    
    /**
     * Generates a proper Zoom URL from a meeting ID
     * @param meetingId The Zoom meeting ID (can be with or without hyphens)
     * @return A properly formatted Zoom URL that can be launched by an Intent
     */
    fun generateZoomUrl(meetingId: String): String {
        // Clean the meeting ID by removing any non-digit characters
        val cleanId = meetingId.replace(Regex("[^0-9]"), "")
        
        // Format for Zoom deep link
        return "zoomus://zoom.us/join?confno=$cleanId"
    }
    
    /**
     * Alternative method to generate a web URL for Zoom meetings
     * Use this if you prefer to open in browser instead of the Zoom app
     * @param meetingId The meeting ID
     * @param context Context needed to retrieve saved domain
     * @return A properly formatted web URL for the Zoom meeting
     */
    fun generateWebUrl(meetingId: String, context: Context? = null): String {
        val cleanId = meetingId.replace(Regex("[^0-9]"), "")
        
        // If no context provided, use default domain
        if (context == null) {
            return "https://$DEFAULT_DOMAIN/j/$cleanId"
        }
        
        // Get user's company domain if available, otherwise use default
        val domain = DomainManager.getSavedDomain(context) ?: DEFAULT_DOMAIN
        return "https://$domain/j/$cleanId"
    }
}