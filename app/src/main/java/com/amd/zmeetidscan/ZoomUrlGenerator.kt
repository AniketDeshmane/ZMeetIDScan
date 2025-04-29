package com.amd.zmeetidscan

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
     * (for users without the Zoom app installed)
     * 
     * @param meetingId The meeting ID (numbers only)
     * @param context Context required to access the custom domain if set
     * @return A web URL that can be opened in a browser
     */
    fun generateWebUrl(meetingId: String, context: Context): String {
        val domain = DomainManager.getSavedDomain(context) ?: DEFAULT_DOMAIN
        val cleanId = meetingId.replace(Regex("[^0-9]"), "")
        
        return "https://$domain/j/$cleanId"
    }
}