package com.byd.clusternavbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.cluster.ClusterCommBridge
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.ui.FloatingPillView
import com.byd.clusternavbridge.ui.MainActivity

class NavClusterService : Service() {

    companion object {
        private const val TAG = "BydNavClusterService"
        private const val CHANNEL_ID = "byd_nav_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: NavClusterService? = null
            private set

        const val ACTION_START = "com.byd.clusternavbridge.action.START"
        const val ACTION_STOP = "com.byd.clusternavbridge.action.STOP"
        const val ACTION_SET_MODE = "com.byd.clusternavbridge.action.SET_MODE"
        const val ACTION_RELEASE_FOCUS = "com.byd.clusternavbridge.action.RELEASE_FOCUS"
        const val EXTRA_MODE = "extra_mode"

        fun startService(context: Context) {
            val intent = Intent(context, NavClusterService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var clusterBridge: ClusterCommBridge
    private var floatingPill: FloatingPillView? = null
    var currentNavState: NavState = NavState()
        private set

    private var preferredMode: Int = ClusterCommBridge.MODE_PICCOLO
    private var manualOverride: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "NavClusterService onCreate")

        clusterBridge = ClusterCommBridge(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Servizio attivo in attesa di navigazione"))

        initFloatingPill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_MODE -> {
                val mode = intent.getIntExtra(EXTRA_MODE, ClusterCommBridge.MODE_PICCOLO)
                setClusterMode(mode, manual = true)
            }
            ACTION_RELEASE_FOCUS -> {
                releaseClusterFocus(manual = true)
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NavClusterService onDestroy")
        floatingPill?.hide()
        clusterBridge.setFocus(false)
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initFloatingPill() {
        if (Settings.canDrawOverlays(this)) {
            floatingPill = FloatingPillView(
                context = this,
                onModeSelected = { mode -> setClusterMode(mode, manual = true) },
                onReleaseFocus = { releaseClusterFocus(manual = true) }
            )
            floatingPill?.show()
        } else {
            Log.w(TAG, "Overlay permission not granted; floating pill deferred")
        }
    }

    fun toggleFloatingPill(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (floatingPill == null) {
            initFloatingPill()
            return true
        }
        return if (floatingPill?.isShown() == true) {
            floatingPill?.hide()
            false
        } else {
            floatingPill?.show()
            true
        }
    }

    fun setClusterMode(mode: Int, manual: Boolean) {
        if (manual) {
            manualOverride = true
        }
        preferredMode = mode
        clusterBridge.setDisplayMode(mode)
        clusterBridge.setFocus(true)
        if (currentNavState.isActive) {
            clusterBridge.sendTbtInfo(currentNavState)
        }
    }

    fun releaseClusterFocus(manual: Boolean) {
        if (manual) {
            manualOverride = false
        }
        clusterBridge.setFocus(false)
    }

    fun onNavStateChanged(navState: NavState) {
        currentNavState = navState
        Log.i(TAG, "Nav state changed: active=${navState.isActive}, source=${navState.sourceName}")

        floatingPill?.updateStatus(navState.isActive, navState.sourceName)

        if (navState.isActive) {
            // Auto-publish to cluster
            clusterBridge.setDisplayMode(preferredMode)
            clusterBridge.setFocus(true)
            clusterBridge.sendTbtInfo(navState)
            updateNotification("Navigazione attiva da ${navState.sourceName}: ${navState.distance} ${navState.instruction}")
        } else {
            // Navigation stopped
            if (!manualOverride) {
                clusterBridge.setFocus(false)
            }
            updateNotification("Servizio attivo in attesa di navigazione")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BYD Cluster Nav Bridge")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }
}
