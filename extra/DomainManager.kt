package com.example.zoomcodescanner

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages company domain storage and retrieval for Zoom URLs
 */
object DomainManager {
    private const val PREFS_NAME = "zoom_domain_prefs"
    private const val KEY_DOMAIN = "company_domain"
    private const val KEY_FIRST_RUN = "first_run"

    /**
     * Checks if this is the first run of the app (domain setup required)
     */
    fun isFirstRun(context: Context): Boolean {
        val prefs = getPreferences(context)
        return prefs.getBoolean(KEY_FIRST_RUN, true)
    }

    /**
     * Marks the app as no longer on first run
     */
    fun markFirstRunComplete(context: Context) {
        val prefs = getPreferences(context)
        prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }

    /**
     * Saves the company domain in SharedPreferences
     * @param domain User input domain (will be sanitized)
     */
    fun saveDomain(context: Context, domain: String) {
        val sanitizedDomain = sanitizeDomain(domain)
        val prefs = getPreferences(context)
        
        prefs.edit()
            .putString(KEY_DOMAIN, sanitizedDomain)
            .putBoolean(KEY_FIRST_RUN, false)
            .apply()
    }

    /**
     * Retrieves the saved domain or returns null if no domain is saved
     */
    fun getSavedDomain(context: Context): String? {
        val prefs = getPreferences(context)
        return prefs.getString(KEY_DOMAIN, null)
    }

    /**
     * Sanitizes user input domain by:
     * - Removing any spaces
     * - Removing any non-alphanumeric or dot characters
     * - Converting to lowercase
     */
    fun sanitizeDomain(domain: String): String {
        // Remove spaces
        var sanitized = domain.replace("\\s+".toRegex(), "")
        
        // Remove any characters except letters, numbers, dots, and hyphens
        sanitized = sanitized.replace("[^a-zA-Z0-9.-]".toRegex(), "")
        
        // Convert to lowercase
        return sanitized.lowercase()
    }

    /**
     * Validate if the domain looks legitimate
     */
    fun isValidDomain(domain: String): Boolean {
        // Basic validation: at least has some characters and contains a dot
        val sanitized = sanitizeDomain(domain)
        return sanitized.isNotEmpty() && sanitized.contains(".")
    }

    /**
     * Helper method to get SharedPreferences
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Clear stored domain (for testing or resetting)
     */
    fun clearDomain(context: Context) {
        val prefs = getPreferences(context)
        prefs.edit()
            .remove(KEY_DOMAIN)
            .putBoolean(KEY_FIRST_RUN, true)
            .apply()
    }
}