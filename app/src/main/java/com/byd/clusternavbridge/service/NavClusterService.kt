package com.byd.clusternavbridge.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.cluster.ClusterCommBridge
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.power.VehiclePowerMonitor
import com.byd.clusternavbridge.ui.ClusterMapActivity
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
    private lateinit var powerMonitor: VehiclePowerMonitor
    private var floatingPill: FloatingPillView? = null
    var currentNavState: NavState = NavState()
        private set

    private var preferredMode: Int = ClusterCommBridge.MODE_GRANDE
    private var manualOverride: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "NavClusterService onCreate")

        clusterBridge = ClusterCommBridge(this)

        // Setup Power Monitor: strictly shut down cluster map when vehicle is OFF
        powerMonitor = VehiclePowerMonitor(this) { isPowered ->
            Log.i(TAG, "Vehicle power state updated: isPowered=$isPowered")
            if (!isPowered) {
                closeClusterMap()
                releaseClusterFocus(manual = false)
                floatingPill?.hide()
            } else {
                initFloatingPill()
                if (currentNavState.isActive) {
                    launchClusterMapOnDisplay1()
                    setClusterMode(preferredMode, manual = false)
                }
            }
        }
        powerMonitor.start()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Servizio attivo in attesa di navigazione"))

        initFloatingPill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_MODE -> {
                val mode = intent.getIntExtra(EXTRA_MODE, ClusterCommBridge.MODE_GRANDE)
                setClusterMode(mode, manual = true)
            }
            ACTION_RELEASE_FOCUS -> {
                releaseClusterFocus(manual = true)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NavClusterService onDestroy")
        powerMonitor.stop()
        closeClusterMap()
        floatingPill?.hide()
        clusterBridge.setFocus(false)
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initFloatingPill() {
        if (!powerMonitor.isPowered) {
            Log.i(TAG, "Vehicle is OFF; floating pill deferred")
            return
        }
        if (Settings.canDrawOverlays(this)) {
            if (floatingPill == null) {
                floatingPill = FloatingPillView(
                    context = this,
                    onModeSelected = { mode -> setClusterMode(mode, manual = true) },
                    onReleaseFocus = { releaseClusterFocus(manual = true) }
                )
            }
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

    fun launchClusterMapOnDisplay1() {
        if (!powerMonitor.isPowered) {
            Log.w(TAG, "Vehicle is OFF; cluster map launch blocked")
            return
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = displayManager.displays
        val targetDisplay = displays.find { it.displayId == 1 }
            ?: displays.firstOrNull { it.name.contains("HDMI", ignoreCase = true) }

        if (targetDisplay != null) {
            Log.i(TAG, "Launching ClusterMapActivity on Display ${targetDisplay.displayId} (${targetDisplay.name})")
            val intent = Intent(this, ClusterMapActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = targetDisplay.displayId
            }
            try {
                startActivity(intent, options.toBundle())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ClusterMapActivity on Display 1", e)
            }
        } else {
            Log.w(TAG, "Display 1 (HDMI) not detected")
        }
    }

    fun closeClusterMap() {
        try {
            sendBroadcast(Intent(ClusterMapActivity.ACTION_CLOSE_CLUSTER_MAP))
        } catch (e: Exception) {
            Log.w(TAG, "Error broadcasting close cluster map", e)
        }
    }

    fun setClusterMode(mode: Int, manual: Boolean) {
        if (!powerMonitor.isPowered && mode != ClusterCommBridge.MODE_OFF) {
            Log.w(TAG, "Vehicle is OFF; refusing cluster mode $mode")
            return
        }
        if (manual) {
            manualOverride = true
        }
        preferredMode = mode
        clusterBridge.setDisplayMode(mode)
        clusterBridge.setFocus(mode != ClusterCommBridge.MODE_OFF)

        if (mode != ClusterCommBridge.MODE_OFF) {
            launchClusterMapOnDisplay1()
            if (currentNavState.isActive) {
                clusterBridge.sendTbtInfo(currentNavState)
            }
        } else {
            closeClusterMap()
        }
    }

    fun releaseClusterFocus(manual: Boolean) {
        if (manual) {
            manualOverride = false
        }
        clusterBridge.setFocus(false)
        closeClusterMap()
    }

    fun onNavStateChanged(navState: NavState) {
        currentNavState = navState
        Log.i(TAG, "Nav state changed: active=${navState.isActive}, source=${navState.sourceName}")

        floatingPill?.updateStatus(navState.isActive, navState.sourceName)

        if (navState.isActive && powerMonitor.isPowered) {
            // Auto-publish to cluster
            clusterBridge.setDisplayMode(preferredMode)
            clusterBridge.setFocus(true)
            clusterBridge.sendTbtInfo(navState)
            launchClusterMapOnDisplay1()

            // Send real-time HUD data to ClusterMapActivity
            val iconId = clusterBridge.mapInstructionToIcon(navState.instruction)
            val updateIntent = Intent(ClusterMapActivity.ACTION_UPDATE_NAV_DATA).apply {
                putExtra(ClusterMapActivity.EXTRA_STREET, navState.instruction)
                putExtra(ClusterMapActivity.EXTRA_DISTANCE, navState.distance)
                putExtra(ClusterMapActivity.EXTRA_ICON_ID, iconId)
                putExtra(ClusterMapActivity.EXTRA_ETA, navState.eta)
                putExtra(ClusterMapActivity.EXTRA_REMAINING_DIST, navState.remainingTimeDistance)
            }
            sendBroadcast(updateIntent)

            updateNotification("Navigazione attiva da ${navState.sourceName}: ${navState.distance} ${navState.instruction}")
        } else if (!navState.isActive) {
            // Navigation stopped
            if (!manualOverride) {
                clusterBridge.setFocus(false)
                closeClusterMap()
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
