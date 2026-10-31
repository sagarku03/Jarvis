package com.example.services

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.di.AndroidEntryPoint
import com.example.domain.model.NotificationItemSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification Listener Service with:
 * 1. Strict OTP & verification code redaction (never reads 4-8 digit OTP codes aloud or sends them to the LLM).
 * 2. Direct inline reply execution via RemoteInput after SafetyGate user confirmation.
 * 3. Notification dismissal by key or sender/title match.
 */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        // Non-UI regex detector for OTP / verification codes
        private val OTP_KEYWORD_REGEX = Regex(
            "(?i)\\b(otp|verification code|security code|passcode|one-time password|pin)\\b"
        )
        private val OTP_DIGITS_REGEX = Regex("\\b\\d{4,8}\\b")

        @Volatile
        private var instance: JarvisNotificationListener? = null

        private val _recentNotifications = MutableStateFlow<List<NotificationItemSnapshot>>(emptyList())
        val recentNotifications: StateFlow<List<NotificationItemSnapshot>> = _recentNotifications.asStateFlow()

        private val replyActionsMap = mutableMapOf<String, Pair<PendingIntent, Array<RemoteInput>>>()

        fun isListenerConnected(): Boolean = instance != null

        fun redactOtpContent(rawTitle: String, rawText: String): Pair<String, Boolean> {
            val combined = "$rawTitle $rawText"
            val containsOtpKeyword = OTP_KEYWORD_REGEX.containsMatchIn(combined)
            val containsDigitCode = OTP_DIGITS_REGEX.containsMatchIn(rawText)

            return if (containsOtpKeyword || containsDigitCode) {
                val redacted = OTP_DIGITS_REGEX.replace(rawText, "[REDACTED-CODE]")
                redacted to true
            } else {
                rawText to false
            }
        }

        fun sendInlineReply(targetQuery: String, replyMessage: String): Pair<Boolean, String> {
            val service = instance
                ?: return false to "Notification Listener access is not enabled."
            val currentList = _recentNotifications.value
            val matched = currentList.firstOrNull {
                it.title.contains(targetQuery, ignoreCase = true) ||
                    it.packageName.contains(targetQuery, ignoreCase = true)
            } ?: return false to "No active notification found matching '$targetQuery'."

            val actionPair = synchronized(replyActionsMap) {
                replyActionsMap[matched.key]
            } ?: return false to "Notification from '${matched.title}' does not support direct reply."

            return try {
                val (pendingIntent, remoteInputs) = actionPair
                val fillInIntent = Intent()
                val resultsBundle = Bundle()
                for (remoteInput in remoteInputs) {
                    resultsBundle.putCharSequence(remoteInput.resultKey, replyMessage)
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, resultsBundle)
                pendingIntent.send(service.applicationContext, 0, fillInIntent)
                true to "Sent reply to '${matched.title}': \"$replyMessage\""
            } catch (e: Exception) {
                false to "Failed to send notification reply: ${e.localizedMessage}"
            }
        }

        fun dismissNotification(targetQuery: String): Pair<Boolean, String> {
            val service = instance
                ?: return false to "Notification Listener access is not enabled."
            val matched = _recentNotifications.value.firstOrNull {
                it.title.contains(targetQuery, ignoreCase = true) ||
                    it.packageName.contains(targetQuery, ignoreCase = true)
            } ?: return false to "No active notification matching '$targetQuery' to dismiss."

            return try {
                service.cancelNotification(matched.key)
                _recentNotifications.value = _recentNotifications.value.filterNot { it.key == matched.key }
                synchronized(replyActionsMap) {
                    replyActionsMap.remove(matched.key)
                }
                true to "Dismissed notification from '${matched.title}'."
            } catch (e: Exception) {
                false to "Could not dismiss notification: ${e.localizedMessage}"
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        processStatusBarNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        _recentNotifications.value = _recentNotifications.value.filterNot { it.key == key }
        synchronized(replyActionsMap) {
            replyActionsMap.remove(key)
        }
    }

    private fun refreshActiveNotifications() {
        runCatching {
            activeNotifications?.forEach { processStatusBarNotification(it) }
        }
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (rawTitle.isEmpty() && rawText.isEmpty()) return

        val (sanitizedText, isRedacted) = redactOtpContent(rawTitle, rawText)

        var hasReply = false
        val actions = sbn.notification.actions
        if (!actions.isNullOrEmpty()) {
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                if (!remoteInputs.isNullOrEmpty() && action.actionIntent != null) {
                    hasReply = true
                    synchronized(replyActionsMap) {
                        replyActionsMap[sbn.key] = action.actionIntent to remoteInputs
                    }
                    break
                }
            }
        }

        val item = NotificationItemSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            title = rawTitle.ifEmpty { sbn.packageName },
            text = sanitizedText,
            hasReplyAction = hasReply,
            isOtpRedacted = isRedacted,
            postedAtMs = sbn.postTime
        )

        val updated = (_recentNotifications.value.filterNot { it.key == sbn.key } + item)
            .sortedByDescending { it.postedAtMs }
            .take(15)
        _recentNotifications.value = updated
    }
}
