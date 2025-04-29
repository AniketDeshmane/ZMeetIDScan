package com.example.zoomcodescanner

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
     * Shows the domain setup dialog for traditional View-based activities
     */
    fun showDialog(context: Context, onComplete: () -> Unit) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Company Domain")
        builder.setMessage("Does your company use a custom Zoom domain? (e.g. company.zoom.us)")
        
        builder.setPositiveButton("Yes") { dialog, _ ->
            dialog.dismiss()
            showDomainInputDialog(context, onComplete)
        }
        
        builder.setNegativeButton("No") { dialog, _ ->
            // Save the default domain
            DomainManager.saveDomain(context, "zoom.us")
            DomainManager.markFirstRunComplete(context)
            dialog.dismiss()
            onComplete()
        }
        
        builder.setCancelable(false)
        builder.show()
    }
    
    private fun showDomainInputDialog(context: Context, onComplete: () -> Unit) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Enter Domain")
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_domain_input, null)
        val editText = view.findViewById<EditText>(R.id.domain_input)
        val errorText = view.findViewById<TextView>(R.id.error_text)
        
        builder.setView(view)
        
        val dialog = builder.create()
        
        view.findViewById<Button>(R.id.save_button).setOnClickListener {
            val domain = editText.text.toString()
            
            if (DomainManager.isValidDomain(domain)) {
                DomainManager.saveDomain(context, domain)
                dialog.dismiss()
                onComplete()
            } else {
                errorText.visibility = android.view.View.VISIBLE
                errorText.text = "Please enter a valid domain"
            }
        }
        
        dialog.setCancelable(false)
        dialog.show()
    }
    
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