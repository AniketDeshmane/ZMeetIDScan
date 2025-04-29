package com.amd.zmeetidscan

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Handles domain setup prompts for first-time users
 */
object DomainSetupDialog {
    
    /**
     * Compose version of the domain setup dialog
     */
    @Composable
    fun DomainSetupDialogCompose(onComplete: () -> Unit) {
        var showDomainInput by remember { mutableStateOf(false) }
        val context = LocalContext.current
        
        if (!showDomainInput) {
            AlertDialog(
                onDismissRequest = { /* Non-dismissible */ },
                title = { Text("Company Domain") },
                text = { Text("Does your company use a custom Zoom domain? (e.g. company.zoom.us)") },
                confirmButton = {
                    Button(onClick = { showDomainInput = true }) {
                        Text("Yes")
                    }
                },
                dismissButton = {
                    Button(onClick = { 
                        DomainManager.saveDomain(context, "zoom.us")
                        DomainManager.markFirstRunComplete(context)
                        onComplete()
                    }) {
                        Text("No")
                    }
                }
            )
        } else {
            DomainInputDialogCompose(onComplete)
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DomainInputDialogCompose(onComplete: () -> Unit) {
        val context = LocalContext.current
        var domain by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        
        Dialog(onDismissRequest = { /* Non-dismissible */ }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Enter Company Domain",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it; error = "" },
                        label = { Text("Domain (e.g. company.zoom.us)") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = error.isNotEmpty(),
                        singleLine = true
                    )
                    
                    if (error.isNotEmpty()) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            if (DomainManager.isValidDomain(domain)) {
                                DomainManager.saveDomain(context, domain)
                                onComplete()
                            } else {
                                error = "Please enter a valid domain"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}