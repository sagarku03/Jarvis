package com.example.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.JarvisApplication
import com.example.R
import com.example.domain.model.AssistantOrbState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating draggable JARVIS orb & 1-tap STOP kill-switch pill over other apps.
 * Enforces the Setup Gate: refuses to run if the Gemini API key is missing or setup is incomplete.
 */
class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var orbStatusDot: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as? JarvisApplication
        if (app == null || !app.appContainer.apiKeyStore.exists()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) {
            attachFloatingOrb()
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachFloatingOrb() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 280
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 64f
                setColor(Color.parseColor("#E6101726"))
                setStroke(2, Color.parseColor("#552DE2E6"))
            }
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(24, 24).apply {
                marginEnd = 16
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8B5CF6"))
            }
        }
        orbStatusDot = dot

        var pillStatusTextView: TextView? = null

        val titleView = TextView(this).apply {
            text = getString(R.string.overlay_chip_label)
            setTextColor(Color.parseColor("#F5F3FF"))
            textSize = 13f
            setPadding(0, 0, 16, 0)
            maxLines = 1
        }
        pillStatusTextView = titleView

        val stopBtn = TextView(this).apply {
            text = getString(R.string.overlay_stop_button)
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(20, 8, 20, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 36f
                setColor(Color.parseColor("#FF6B87"))
            }
            setOnClickListener {
                AssistantController.stopAll(this@OverlayService)
            }
        }

        root.addView(dot)
        root.addView(titleView)
        root.addView(stopBtn)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchDownTime = 0L

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchDownTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val duration = System.currentTimeMillis() - touchDownTime
                    if (dx < 12 && dy < 12) {
                        if (duration > 600) {
                            // Long-press stops the assistant
                            AssistantController.stopAll(this@OverlayService)
                        } else {
                            // Tap opens Voice Call in app
                            AssistantController.startVoiceCall(this@OverlayService)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(root, params)
            overlayView = root
        }

        val app = application as? JarvisApplication
        app?.appContainer?.let { container ->
            serviceScope.launch {
                container.agentOrchestrator.orbState.collectLatest { state ->
                    val colorHex = when (state) {
                        AssistantOrbState.IDLE -> "#8B5CF6"
                        AssistantOrbState.LISTENING -> "#2DE2E6"
                        AssistantOrbState.THINKING -> "#B388FF"
                        AssistantOrbState.SPEAKING -> "#3DDC97"
                        AssistantOrbState.PAUSED_KILLSWITCH -> "#FF6B87"
                    }
                    (orbStatusDot?.background as? GradientDrawable)?.setColor(Color.parseColor(colorHex))
                    if (state == AssistantOrbState.IDLE) {
                        pillStatusTextView?.text = getString(R.string.overlay_chip_label)
                    } else if (state == AssistantOrbState.LISTENING) {
                        pillStatusTextView?.text = getString(R.string.voice_call_listening)
                    } else if (state == AssistantOrbState.SPEAKING) {
                        pillStatusTextView?.text = getString(R.string.voice_call_speaking)
                    }
                }
            }

            serviceScope.launch {
                container.agentOrchestrator.orbCaption.collectLatest { caption ->
                    if (!caption.isNullOrBlank()) {
                        pillStatusTextView?.text = caption
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        fun startIfPermitted(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val app = context.applicationContext as? JarvisApplication
            if (app != null && !app.appContainer.apiKeyStore.exists()) return
            val intent = Intent(context, OverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
