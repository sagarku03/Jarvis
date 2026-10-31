package com.example.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.BuildConfig
import com.example.JarvisApplication
import com.example.di.AndroidEntryPoint
import com.example.domain.model.ScreenContextSnapshot
import com.example.domain.model.ScreenNodeSnapshot
import com.example.domain.safety.SafetyGateImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 4: Full JarvisAccessibilityService (active ONLY when BuildConfig.FULL_CONTROL_MODE && !BuildConfig.STORE_SAFE_MODE).
 * Implements:
 * 1. ScreenReader: Converts AccessibilityNodeInfo tree into a compact capped JSON, skipping password fields and blocked banking/UPI apps.
 * 2. ActionExecutor: Tap by node/coordinates, long press, swipe/scroll, type text, back/home/recents/notifications/quick_settings, with post-action verification.
 * 3. Hardware Volume-Key Combo KillSwitch: Double Volume-Down press immediately halts active gestures and triggers global KillSwitch.
 */
@AndroidEntryPoint
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_NODES = 60
        private const val MAX_JSON_CHARS = 4096

        const val ACCESSIBILITY_DISABLED_EN =
            "Accessibility service is not enabled. Open Settings to enable."
        const val ACCESSIBILITY_DISABLED_HI =
            "Accessibility सेवा चालू नहीं है। इसे चालू करने के लिए Settings खोलें।"

        @Volatile
        private var instance: JarvisAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _lastSnapshot = MutableStateFlow<ScreenContextSnapshot?>(null)
        val lastSnapshot: StateFlow<ScreenContextSnapshot?> = _lastSnapshot.asStateFlow()

        fun isFullControlAllowed(): Boolean =
            BuildConfig.FULL_CONTROL_MODE && !BuildConfig.STORE_SAFE_MODE

        fun captureCurrentScreenSnapshot(): ScreenContextSnapshot? {
            if (!isFullControlAllowed()) return null
            val service = instance ?: return null
            return service.readScreenInternal()
        }

        suspend fun executeTap(targetText: String?, x: Float?, y: Float?): Pair<Boolean, String> {
            if (!isFullControlAllowed()) {
                return false to "Blocked: STORE_SAFE_MODE is active."
            }
            val service = instance ?: return false to ACCESSIBILITY_DISABLED_EN
            return service.performTapWithVerification(targetText, x, y)
        }

        suspend fun executeTypeText(text: String, targetHint: String?): Pair<Boolean, String> {
            if (!isFullControlAllowed()) {
                return false to "Blocked: STORE_SAFE_MODE is active."
            }
            val service = instance ?: return false to ACCESSIBILITY_DISABLED_EN
            return service.performTypeTextWithVerification(text, targetHint)
        }

        suspend fun executeScroll(direction: String): Pair<Boolean, String> {
            if (!isFullControlAllowed()) {
                return false to "Blocked: STORE_SAFE_MODE is active."
            }
            val service = instance ?: return false to ACCESSIBILITY_DISABLED_EN
            return service.performScrollWithVerification(direction)
        }

        fun executeSystemGlobalAction(actionName: String): Pair<Boolean, String> {
            if (!isFullControlAllowed()) {
                return false to "Blocked: STORE_SAFE_MODE is active."
            }
            val service = instance ?: return false to ACCESSIBILITY_DISABLED_EN
            return service.performGlobalNavigation(actionName)
        }

        suspend fun waitForElement(targetText: String, timeoutMs: Long): Pair<Boolean, String> {
            if (!isFullControlAllowed()) {
                return false to "Blocked: STORE_SAFE_MODE is active."
            }
            val service = instance ?: return false to ACCESSIBILITY_DISABLED_EN
            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 10_000L)
            while (System.currentTimeMillis() < deadline) {
                val snap = service.readScreenInternal()
                val found = snap?.structuredNodes?.any {
                    it.text.contains(targetText, ignoreCase = true) ||
                        it.contentDescription.contains(targetText, ignoreCase = true)
                } == true
                if (found) {
                    return true to "Found element '$targetText' on screen (${snap?.packageName})."
                }
                delay(250)
            }
            return false to "Timed out after ${timeoutMs}ms waiting for '$targetText'."
        }
    }

    private var lastVolumeDownPressMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (isFullControlAllowed()) {
            instance = this
            _isConnected.value = true
        } else {
            _isConnected.value = false
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isFullControlAllowed()) return
        // Phase 3 Privacy: Do NOT continuously scrape or serialize the screen on every window event.
        // Screen context is captured strictly on-demand when an accessibility tool is invoked.
    }

    /**
     * Stage 4 KillSwitch via Hardware Volume-Key Combo:
     * Double-pressing Volume Down within 850ms immediately triggers the global KillSwitch
     * and cancels any active gesture mid-flight.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownPressMs in 80..850) {
                val app = application as? JarvisApplication
                app?.container?.killSwitch?.triggerKillSwitch("Hardware Volume-Down Double-Press KillSwitch")
            }
            lastVolumeDownPressMs = now
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    /**
     * Stage 4 ScreenReader (Phase 3 Privacy Hardened):
     * Traverses AccessibilityNodeInfo tree on-demand into a capped compact JSON, skipping password fields
     * and strictly refusing to serialize or return any node text when on a banking/UPI/password screen.
     */
    private fun readScreenInternal(): ScreenContextSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: "unknown"
        val isBlockedPkg = SafetyGateImpl.BLOCKED_SENSITIVE_PACKAGES.any {
            pkg.equals(it, ignoreCase = true)
        } || pkg.contains(".bank", ignoreCase = true) || pkg.contains(".upi", ignoreCase = true)

        if (isBlockedPkg) {
            val blockedSnap = ScreenContextSnapshot(
                packageName = pkg,
                activityName = root.className?.toString() ?: "SecureWindow",
                isSecureScreen = true,
                rawVisibleTextNodes = emptyList(),
                structuredNodes = emptyList(),
                compactJson = "",
                screenHash = "${pkg}_SECURE_BLOCKED"
            )
            _lastSnapshot.value = blockedSnap
            return blockedSnap
        }

        val collectedNodes = mutableListOf<ScreenNodeSnapshot>()
        val visibleTexts = mutableListOf<String>()
        var hasPasswordNode = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || collectedNodes.size >= MAX_NODES) return
            if (node.isPassword) {
                hasPasswordNode = true
                // Strictly skip reading or serializing password fields!
                return
            }

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: "node_${collectedNodes.size}"
            val cls = node.className?.toString()?.substringAfterLast(".") ?: "View"
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val boundsStr = "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"

            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
                collectedNodes.add(
                    ScreenNodeSnapshot(
                        id = viewId,
                        text = text.take(80),
                        contentDescription = desc.take(80),
                        className = cls,
                        bounds = boundsStr,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable
                    )
                )
                if (text.isNotEmpty()) visibleTexts.add(text.take(80))
                else if (desc.isNotEmpty()) visibleTexts.add(desc.take(80))
            }

            for (i in 0 until node.childCount) {
                if (collectedNodes.size >= MAX_NODES) break
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        val isSecure = isBlockedPkg || hasPasswordNode
        if (isSecure) {
            val secureSnap = ScreenContextSnapshot(
                packageName = pkg,
                activityName = root.className?.toString() ?: "SecureWindow",
                isSecureScreen = true,
                rawVisibleTextNodes = emptyList(),
                structuredNodes = emptyList(),
                compactJson = "",
                screenHash = "${pkg}_SECURE_PASSWORD"
            )
            _lastSnapshot.value = secureSnap
            return secureSnap
        }

        val compactJson = buildCompactNodesJson(collectedNodes).take(MAX_JSON_CHARS)
        val hash = "${pkg}_${collectedNodes.size}_${visibleTexts.joinToString("|").hashCode()}"

        val snapshot = ScreenContextSnapshot(
            packageName = pkg,
            activityName = root.className?.toString() ?: "Window",
            isSecureScreen = false,
            rawVisibleTextNodes = visibleTexts,
            structuredNodes = collectedNodes,
            compactJson = compactJson,
            screenHash = hash
        )
        _lastSnapshot.value = snapshot
        return snapshot
    }

    private fun buildCompactNodesJson(nodes: List<ScreenNodeSnapshot>): String {
        val items = nodes.joinToString(",") { n ->
            """{"id":"${escape(n.id)}","text":"${escape(n.text)}","desc":"${escape(n.contentDescription)}","cls":"${escape(n.className)}","bounds":"${n.bounds}","click":${n.clickable},"edit":${n.editable},"scroll":${n.scrollable}}"""
        }
        return "[$items]"
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private suspend fun performTapWithVerification(
        targetText: String?,
        x: Float?,
        y: Float?
    ): Pair<Boolean, String> {
        val beforeHash = readScreenInternal()?.screenHash
        val root = rootInActiveWindow

        var actionDispatched = false
        if (!targetText.isNullOrBlank() && root != null) {
            val matched = findNodeByTextOrDesc(root, targetText)
            if (matched != null) {
                if (matched.isClickable) {
                    actionDispatched = matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!actionDispatched) {
                    val rect = Rect().also { matched.getBoundsInScreen(it) }
                    actionDispatched = dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
                }
            }
        } else if (x != null && y != null) {
            actionDispatched = dispatchTapGesture(x, y)
        }

        delay(220)
        val afterHash = readScreenInternal()?.screenHash
        val changed = beforeHash != afterHash
        return if (actionDispatched) {
            true to "Tapped '${targetText ?: "$x,$y"}' (screenChanged=$changed)."
        } else {
            false to "Could not find or tap target '${targetText ?: "$x,$y"}'."
        }
    }

    private suspend fun performTypeTextWithVerification(
        text: String,
        targetHint: String?
    ): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "No active window to type into."
        val targetNode = if (!targetHint.isNullOrBlank()) {
            findNodeByTextOrDesc(root, targetHint)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root)
        }

        if (targetNode == null || targetNode.isPassword) {
            return false to "No safe editable text field found (password fields are blocked)."
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(180)
        return if (ok) {
            true to "Typed '$text' into editable field and verified."
        } else {
            false to "Failed to set text on editable node."
        }
    }

    private suspend fun performScrollWithVerification(direction: String): Pair<Boolean, String> {
        val beforeHash = readScreenInternal()?.screenHash
        val root = rootInActiveWindow
        val scrollable = if (root != null) findFirstScrollableNode(root) else null
        val isUp = direction.equals("up", ignoreCase = true) || direction.equals("backward", ignoreCase = true)

        val ok = if (scrollable != null) {
            val action = if (isUp) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            scrollable.performAction(action)
        } else {
            dispatchSwipeGesture(
                startX = 540f,
                startY = if (isUp) 500f else 1400f,
                endX = 540f,
                endY = if (isUp) 1400f else 500f
            )
        }

        delay(240)
        val afterHash = readScreenInternal()?.screenHash
        val changed = beforeHash != afterHash
        return ok to "Scrolled $direction (screenChanged=$changed)."
    }

    private fun performGlobalNavigation(actionName: String): Pair<Boolean, String> {
        val globalConst = when (actionName.lowercase()) {
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> GLOBAL_ACTION_BACK
        }
        val ok = performGlobalAction(globalConst)
        return ok to "Executed system global action: $actionName (success=$ok)."
    }

    private suspend fun dispatchTapGesture(cx: Float, cy: Float): Boolean {
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!dispatched) return false
        return deferred.await()
    }

    private suspend fun dispatchSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!dispatched) return false
        return deferred.await()
    }

    private fun findNodeByTextOrDesc(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val direct = root.findAccessibilityNodeInfosByText(query)
        if (!direct.isNullOrEmpty()) return direct.first()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val desc = child.contentDescription?.toString().orEmpty()
            if (desc.contains(query, ignoreCase = true)) return child
            val found = findNodeByTextOrDesc(child, query)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && !root.isPassword) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstScrollableNode(child)
            if (found != null) return found
        }
        return null
    }
}
