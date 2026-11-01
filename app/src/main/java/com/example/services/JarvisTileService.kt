package com.example.services

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.JarvisApplication
import com.example.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings Tile trigger for JARVIS push-to-talk voice assistant.
 * Checks that the BYO Gemini API key and first-launch setup gate are unlocked before starting listening.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as? JarvisApplication
        val unlocked = app?.let {
            val hasKey = it.appContainer.apiKeyStore.exists()
            val setupDone = runBlocking { it.appContainer.settingsRepository.settingsFlow.first().setupComplete }
            hasKey && setupDone
        } ?: false

        qsTile?.apply {
            state = if (unlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "JARVIS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (unlocked) "Tap to talk" else "Setup required"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AssistantController.EXTRA_START_VOICE_CALL, true)
            putExtra(EXTRA_START_PUSH_TO_TALK, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                701,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val EXTRA_START_PUSH_TO_TALK = "extra_start_push_to_talk"
    }
}
