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
import com.byd.clusternavbridge.power.VehiclePowerMonitor
import com.byd.clusternavbridge.ui.FloatingPillView
import com.byd.clusternavbridge.ui.MainActivity

/**
 * NavClusterService v1.3.0 - Modalità Semplice (TBT Only)
 *
 * Riceve gli eventi di svolta da Google Maps ReVanced e ABRP tramite NavNotificationListener
 * e li inoltra direttamente a ClusterCommBridge verso il quadro strumenti DiLink.
 *
 * Completamente disaccoppiato da Display 1: non apre activity secondarie né display esterni.
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
    var currentNavState: NavState = NavState()
        private set

    private var tbtEnabledUser: Boolean = true

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "NavClusterService onCreate (v1.3.0 - Modalità Semplice)")

        clusterBridge = ClusterCommBridge(this)

        // Monitor di alimentazione veicolo: spegne il TBT a cruscotto se l'auto è spenta (ACC OFF)
        powerMonitor = VehiclePowerMonitor(this) { isPowered ->
            Log.i(TAG, "Vehicle power state updated: isPowered=$isPowered")
            if (!isPowered) {
                clusterBridge.stopTbtMode()
                floatingPill?.hide()
            } else {
                initFloatingPill()
                if (currentNavState.isActive && tbtEnabledUser) {
                    clusterBridge.sendTbtInfo(currentNavState)
                }
            }
        }
        powerMonitor.start()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Servizio TBT attivo - Modalità Semplice"))

        initFloatingPill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE_TBT -> {
                tbtEnabledUser = true
                if (currentNavState.isActive && powerMonitor.isPowered) {
                    clusterBridge.sendTbtInfo(currentNavState)
                } else {
                    clusterBridge.startTbtMode()
                }
            }
            ACTION_DISABLE_TBT -> {
                tbtEnabledUser = false
                clusterBridge.stopTbtMode()
            }
            ACTION_TEST_TBT -> {
                tbtEnabledUser = true
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

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NavClusterService onDestroy")
        powerMonitor.stop()
        floatingPill?.hide()
        clusterBridge.stopTbtMode()
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
                    onTbtEnabled = { enabled ->
                        tbtEnabledUser = enabled
                        if (enabled) {
                            if (currentNavState.isActive) {
                                clusterBridge.sendTbtInfo(currentNavState)
                            } else {
                                clusterBridge.startTbtMode()
                            }
                        } else {
                            clusterBridge.stopTbtMode()
                        }
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
        return if (floatingPill?.isShown() == true) {
            floatingPill?.hide()
            false
        } else {
            floatingPill?.show()
            true
        }
    }

    fun onNavStateChanged(navState: NavState) {
        currentNavState = navState
        Log.i(TAG, "Nav state changed: active=${navState.isActive}, source=${navState.sourceName}, road='${navState.instruction}', dist='${navState.distance}'")

        floatingPill?.updateStatus(navState.isActive, navState.sourceName)

        if (navState.isActive && powerMonitor.isPowered && tbtEnabledUser) {
            clusterBridge.sendTbtInfo(navState)
            updateNotification("${navState.sourceName}: ${navState.distance} ${navState.instruction}")
        } else if (!navState.isActive) {
            clusterBridge.stopTbtMode()
            updateNotification("Servizio TBT attivo in attesa di navigazione")
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
            .setContentTitle("BYD Cluster Nav Bridge (Semplice)")
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
