package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.R
import com.example.di.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Floating Overlay Orb Service with instant one-tap STOP (KillSwitch) pill.
 * Styled with Aurora Violet Glass tokens.
 */
@AndroidEntryPoint
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_overlay_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_SHOW = "com.example.services.ACTION_SHOW_OVERLAY"
        const val ACTION_HIDE = "com.example.services.ACTION_HIDE_OVERLAY"

        private val _isOverlayShowing = MutableStateFlow(false)
        val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            removeOverlayView()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (Settings.canDrawOverlays(this)) {
            showFloatingOrbOverlay()
        } else {
            _isOverlayShowing.value = false
        }

        return START_STICKY
    }

    private fun showFloatingOrbOverlay() {
        if (overlayView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 18, 28, 18)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#EB181132"),
                    Color.parseColor("#EB0E0A1F")
                )
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 64f
                setStroke(3, Color.parseColor("#8B5CF6"))
            }
        }

        val orbTitle = TextView(this).apply {
            text = "● JARVIS  "
            setTextColor(Color.parseColor("#F5F3FF"))
            textSize = 13f
            setOnClickListener {
                val openIntent = Intent(this@OverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(openIntent)
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP"
            setTextColor(Color.parseColor("#F5F3FF"))
            textSize = 11f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(Color.parseColor("#33FF6B87"))
                setStroke(2, Color.parseColor("#99FF6B87"))
            }
            setOnClickListener {
                val appContainer = (application as? JarvisApplication)?.container
                appContainer?.killSwitch?.triggerKillSwitch("Stopped via Floating Overlay KillSwitch")
                removeOverlayView()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        container.addView(orbTitle)
        container.addView(stopButton)

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
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 140
        }

        runCatching {
            wm.addView(container, params)
            overlayView = container
            _isOverlayShowing.value = true
        }
    }

    private fun removeOverlayView() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        _isOverlayShowing.value = false
    }

    override fun onDestroy() {
        removeOverlayView()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Overlay Orb",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.overlay_service_notification_title))
            .setContentText(getString(R.string.overlay_service_notification_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
