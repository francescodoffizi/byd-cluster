package com.byd.clusternavbridge.service

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
import com.byd.clusternavbridge.ui.ClusterPresentation
import com.byd.clusternavbridge.ui.FloatingPillView
import com.byd.clusternavbridge.ui.MainActivity

/**
 * NavClusterService v1.4.0
 *
 * Supporta in modo coordinato le 4 modalità del quadro strumenti DiLink 100:
 * - MODE_OFF (0)      : Cruscotto standard tachimetro OEM
 * - MODE_PICCOLO (1)  : Mappa 3D nel riquadro centrale + handshake Modo 5
 * - MODE_SEMPLICE (2) : Indicatore sportivo frecce TBT vettoriali QNX (zero GPU)
 * - MODE_GRANDE (3)   : Mappa 3D a pieno schermo panoramico + handshake Modo 6
 *
 * Mantiene Google Maps / ABRP intatti su Display 0 senza distacco né crash.
 */
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
        const val EXTRA_MODE = "extra_mode"

        const val ACTION_ENABLE_TBT = "com.byd.clusternavbridge.action.ENABLE_TBT"
        const val ACTION_DISABLE_TBT = "com.byd.clusternavbridge.action.DISABLE_TBT"
        const val ACTION_TEST_TBT = "com.byd.clusternavbridge.action.TEST_TBT"

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
    private var clusterPresentation: ClusterPresentation? = null

    var currentNavState: NavState = NavState()
        private set

    var currentMode: Int = ClusterCommBridge.MODE_SEMPLICE
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "NavClusterService onCreate (v1.4.0 Multi-Modale)")

        clusterBridge = ClusterCommBridge(this)
        clusterBridge.bindDirectService()

        powerMonitor = VehiclePowerMonitor(this) { isPowered ->
            Log.i(TAG, "Vehicle power state updated: isPowered=$isPowered")
            if (!isPowered) {
                closeClusterPresentation()
                clusterBridge.switchClusterMode(ClusterCommBridge.MODE_OFF)
                floatingPill?.hide()
            } else {
                initFloatingPill()
                if (currentNavState.isActive) {
                    setMode(currentMode)
                }
            }
        }
        powerMonitor.start()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Navigazione Cluster DiLink attiva"))

        initFloatingPill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        clusterBridge.bindDirectService()
        when (intent?.action) {
            ACTION_SET_MODE -> {
                val mode = intent.getIntExtra(EXTRA_MODE, ClusterCommBridge.MODE_SEMPLICE)
                setMode(mode)
            }
            ACTION_ENABLE_TBT -> {
                setMode(ClusterCommBridge.MODE_SEMPLICE)
            }
            ACTION_DISABLE_TBT -> {
                setMode(ClusterCommBridge.MODE_OFF)
            }
            ACTION_TEST_TBT -> {
                val testState = NavState(
                    isActive = true,
                    sourcePackage = "com.google.android.apps.maps",
                    sourceName = "Google Maps (Test)",
                    distance = "350 m",
                    instruction = "Tra 350m svolta a destra in Via Roma",
                    eta = "18:45",
                    remainingTimeDistance = "12 min • 4.5 km"
                )
                onNavStateChanged(testState)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun setMode(mode: Int) {
        Log.i(TAG, "Setting mode to $mode")
        currentMode = mode
        clusterBridge.switchClusterMode(mode)

        when (mode) {
            ClusterCommBridge.MODE_PICCOLO, ClusterCommBridge.MODE_GRANDE -> {
                openClusterPresentation()
            }
            ClusterCommBridge.MODE_SEMPLICE, ClusterCommBridge.MODE_OFF -> {
                closeClusterPresentation()
            }
        }

        floatingPill?.updateMode(mode)
        updateForegroundNotification(mode)
    }

    private fun openClusterPresentation() {
        if (!powerMonitor.isPowered) {
            Log.i(TAG, "Vehicle is OFF; presentation deferred")
            return
        }

        if (clusterPresentation != null && clusterPresentation?.isShowing == true) {
            clusterPresentation?.updateNavState(currentNavState)
            return
        }

        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val targetDisplay = if (presentationDisplays.isNotEmpty()) {
            presentationDisplays[0]
        } else {
            dm.displays.firstOrNull { it.displayId == 1 }
        }

        if (targetDisplay != null) {
            try {
                clusterPresentation = ClusterPresentation(this, targetDisplay).apply {
                    show()
                    updateNavState(currentNavState)
                }
                Log.i(TAG, "ClusterPresentation opened on display ${targetDisplay.displayId} (${targetDisplay.name})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ClusterPresentation on display", e)
            }
        } else {
            Log.w(TAG, "No secondary display available for presentation")
        }
    }

    private fun closeClusterPresentation() {
        try {
            clusterPresentation?.dismiss()
            clusterPresentation?.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing presentation: ${e.message}")
        } finally {
            clusterPresentation = null
        }
    }

    fun onNavStateChanged(state: NavState) {
        currentNavState = state
        Log.i(TAG, "onNavStateChanged: active=${state.isActive}, mode=$currentMode, instr='${state.instruction}'")

        if (state.isActive) {
            if (currentMode == ClusterCommBridge.MODE_OFF) {
                setMode(ClusterCommBridge.MODE_SEMPLICE)
            } else {
                clusterBridge.sendTbtInfo(state)
                clusterPresentation?.updateNavState(state)
            }
        } else {
            setMode(ClusterCommBridge.MODE_OFF)
        }

        floatingPill?.updateNavState(state)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NavClusterService onDestroy")
        powerMonitor.stop()
        closeClusterPresentation()
        floatingPill?.hide()
        clusterBridge.switchClusterMode(ClusterCommBridge.MODE_OFF)
        clusterBridge.unbindService()
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
                    onModeSelected = { mode ->
                        setMode(mode)
                    }
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
        return if (floatingPill?.isShowing == true) {
            floatingPill?.hide()
            false
        } else {
            floatingPill?.show()
            true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BYD Cluster Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifiche cluster quadro strumenti BYD"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BYD Cluster Nav Bridge v1.4.0")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateForegroundNotification(mode: Int) {
        val text = when (mode) {
            ClusterCommBridge.MODE_PICCOLO -> "Attivo: Mappa 3D Piccolo"
            ClusterCommBridge.MODE_GRANDE -> "Attivo: Mappa 3D Full-Screen"
            ClusterCommBridge.MODE_SEMPLICE -> "Attivo: Frecce TBT Semplice"
            else -> "Standby (Tachimetro OEM)"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }
}
