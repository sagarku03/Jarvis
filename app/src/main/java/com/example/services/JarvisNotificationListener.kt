package com.example.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.di.AndroidEntryPoint
import com.example.domain.model.CapturedNotificationItem
import com.example.domain.safety.SafetyGateImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Full JarvisNotificationListener.
 * Captures incoming notifications (excluding OTPs, PINs, CVVs, passwords, bank debit/credit alerts,
 * and blocked banking packages), summarizes unread items, and executes confirmed quick-replies via
 * RemoteInput PendingIntents.
 */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private val _isListenerConnected = MutableStateFlow(false)
        val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

        private val _recentNotifications = MutableStateFlow<List<CapturedNotificationItem>>(emptyList())
        val recentNotifications: StateFlow<List<CapturedNotificationItem>> =
            _recentNotifications.asStateFlow()

        private val replyActionsMap = ConcurrentHashMap<String, Notification.Action>()

        private val SENSITIVE_AUTH_PATTERNS = listOf(
            "otp",
            "verification code",
            "one-time",
            "one time password",
            "security code",
            "passcode",
            "cvv",
            "mpin",
            "upi pin",
            "password",
            "ओटीपी",
            "पिन",
            "कोड",
            "सत्यापन",
            "पासवर्ड",
            "गुप्त कोड"
        )

        private val BANK_ALERT_KEYWORDS = listOf(
            "debited",
            "credited",
            "a/c",
            "acct",
            "खाते से",
            "डेबिट",
            "क्रेडिट",
            "भुगतान"
        )

        private val CURRENCY_PATTERNS = listOf(
            "rs.",
            "rs ",
            "inr",
            "₹"
        )

        /**
         * Phase 1 Security Filter:
         * Skips OTP/PIN/CVV/password patterns in English + Hindi (ओटीपी, पिन, कोड),
         * bank debit/credit alert amounts, and all notifications from SafetyGate-blocked banking packages.
         */
        fun shouldFilterSensitiveNotification(
            packageName: String,
            title: String,
            text: String
        ): Boolean {
            val normalizedPkg = packageName.trim().lowercase()
            if (SafetyGateImpl.BLOCKED_SENSITIVE_PACKAGES.any { normalizedPkg == it.lowercase() } ||
                normalizedPkg.contains(".bank") ||
                normalizedPkg.contains(".upi") ||
                normalizedPkg.contains("passkey")
            ) {
                return true
            }

            val combined = "$title $text".lowercase()
            if (SENSITIVE_AUTH_PATTERNS.any { pattern -> combined.contains(pattern) }) {
                return true
            }

            val hasBankKeyword = BANK_ALERT_KEYWORDS.any { kw -> combined.contains(kw) }
            val hasCurrencyOrDigits = CURRENCY_PATTERNS.any { cur -> combined.contains(cur) } ||
                Regex("""\b\d{4,6}\b""").containsMatchIn(combined)
            if (hasBankKeyword && hasCurrencyOrDigits) {
                return true
            }

            return false
        }

        fun summarizeNotifications(): String {
            val items = _recentNotifications.value.take(5)
            if (items.isEmpty()) return "No unread notifications."
            return items.joinToString(" | ") { item ->
                "[${item.appName}] ${item.title}: ${item.text}"
            }
        }

        /**
         * Phase 1 Quick-Reply:
         * Returns success=true ONLY if a valid RemoteInput action exists and PendingIntent.send() succeeded.
         */
        fun sendConfirmedQuickReply(notificationKey: String?, replyText: String): Pair<Boolean, String> {
            val targetItem = if (!notificationKey.isNullOrBlank()) {
                _recentNotifications.value.firstOrNull { it.key == notificationKey }
            } else {
                _recentNotifications.value.firstOrNull { it.canQuickReply }
            } ?: return false to "No replyable notification found."

            val action = replyActionsMap[targetItem.key]
                ?: return false to "Quick-reply failed: Notification '${targetItem.title}' does not expose a reply action."

            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                return false to "Quick-reply failed: Notification '${targetItem.title}' has no RemoteInput fields."
            }

            val pendingIntent = action.actionIntent
                ?: return false to "Quick-reply failed: Notification '${targetItem.title}' has no reply PendingIntent."

            return runCatching {
                val fillInIntent = Intent()
                val bundle = Bundle()
                for (remoteInput in remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)
                pendingIntent.send(null, 0, fillInIntent)
                true to "Sent quick-reply to ${targetItem.title} (${targetItem.appName}): \"$replyText\""
            }.getOrElse { e ->
                false to "Quick-reply failed: ${e.localizedMessage ?: "PendingIntent send error"}"
            }
        }

        internal fun clearNotificationsForTest() {
            _recentNotifications.value = emptyList()
            replyActionsMap.clear()
        }

        internal fun putNotificationWithoutActionForTest(item: CapturedNotificationItem) {
            _recentNotifications.value = listOf(item)
            replyActionsMap.remove(item.key)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isListenerConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isListenerConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        if (shouldFilterSensitiveNotification(pkg, title, text)) {
            return
        }

        val replyAction = notification.actions?.firstOrNull { act ->
            !act.remoteInputs.isNullOrEmpty() && act.actionIntent != null
        }
        if (replyAction != null) {
            replyActionsMap[sbn.key] = replyAction
        }

        val item = CapturedNotificationItem(
            key = sbn.key,
            packageName = pkg,
            appName = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            title = title,
            text = text,
            timestamp = sbn.postTime,
            canQuickReply = replyAction != null
        )

        val updated = (listOf(item) + _recentNotifications.value.filterNot { it.key == sbn.key }).take(15)
        _recentNotifications.value = updated
    }
}
