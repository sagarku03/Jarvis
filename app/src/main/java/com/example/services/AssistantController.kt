package com.example.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.JarvisApplication
import com.example.MainActivity

/**
 * Unified controller for triggering JARVIS assistant operations from
 * the Floating Overlay Pill, Quick Settings Tile, Notification Actions, and Wake Phrase Service.
 */
object AssistantController {

    const val EXTRA_START_VOICE_CALL = "com.example.jarvis.EXTRA_START_VOICE_CALL"
    const val EXTRA_START_PUSH_TO_TALK = "com.example.jarvis.EXTRA_START_PUSH_TO_TALK"

    fun startVoiceCall(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_START_VOICE_CALL, true)
        }
        context.startActivity(intent)
    }

    fun startVoiceCallPendingIntent(context: Context, requestCode: Int = 801): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_START_VOICE_CALL, true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    fun stopAll(context: Context) {
        val app = context.applicationContext as? JarvisApplication ?: return
        val container = app.appContainer
        container.killSwitch.trigger("AssistantController STOP triggered")
        container.voiceSessionManager.stopAll()
        container.agentOrchestrator.cancelActiveTurn()
    }
}
