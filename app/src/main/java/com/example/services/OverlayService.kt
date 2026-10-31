package com.example.services

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
import androidx.core.content.ContextCompat
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.R
import com.example.di.AndroidEntryPoint

/**
 * Floating draggable Aurora Violet Glass orb overlay over other apps.
 * Only starts when `Settings.canDrawOverlays(context)` is granted and user enables it.
 * Provides quick assistant launch and a 1-tap emergency STOP button tied to the global KillSwitch.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (floatingView == null) {
            showFloatingOrb()
        }
        return START_STICKY
    }

    private fun showFloatingOrb() {
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
            y = 260
        }

        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.parseColor("#E60F1322")) // Aurora Surface #0F1322 with glass alpha
                setStroke((1.5f * density).toInt(), Color.parseColor("#8B5CF6")) // Electric Violet stroke
            }
            elevation = 12f * density
        }

        val orbChip = TextView(this).apply {
            text = getString(R.string.overlay_chip_label)
            setTextColor(Color.parseColor("#F5F7FF"))
            textSize = 12f
            setPadding(0, 0, (10 * density).toInt(), 0)
            setOnClickListener {
                val launchIntent = Intent(this@OverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(launchIntent)
            }
        }

        val stopButton = TextView(this).apply {
            text = getString(R.string.overlay_stop_button)
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 12f
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(Color.parseColor("#EF4444")) // Coral Red #EF4444
            }
            setOnClickListener {
                val app = applicationContext as? JarvisApplication
                app?.appContainer?.killSwitch?.trigger("Floating overlay STOP pressed")
                VoiceForegroundService.stopService(this@OverlayService)
                stopSelf()
            }
        }

        container.addView(orbChip)
        container.addView(stopButton)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            floatingView = container
        }
    }

    override fun onDestroy() {
        floatingView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        floatingView = null
        super.onDestroy()
    }

    companion object {
        fun startIfPermitted(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, OverlayService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
