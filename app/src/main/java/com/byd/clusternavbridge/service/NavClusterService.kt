package com.byd.clusternavbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.cluster.ClusterCommBridge
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.power.VehiclePowerMonitor
import com.byd.clusternavbridge.ui.ClusterPresentation
import com.byd.clusternavbridge.ui.FloatingPillView
import com.byd.clusternavbridge.ui.MainActivity
import com.byd.clusternavbridge.voice.VoiceGuidanceManager

/**
 * NavClusterService v1.5.8
 *
 * Supporta in modo coordinato le 4 modalità del quadro strumenti DiLink 100:
 * - MODE_OFF (1)      : Cruscotto standard tachimetro OEM
 * - MODE_SEMPLICE (2) : Card HUD svolta su sfondo nero puro nel riquadro destro (zero GPU mappa)
 * - MODE_PICCOLO (3)  : Mappa 3D + Card HUD nel riquadro destro + handshake Modo 5
 * - MODE_GRANDE (4)   : Mappa 3D a pieno schermo panoramico + Card HUD + handshake Modo 6
 *
 * Mantiene Google Maps / ABRP / HERE WeGo intatti su Display 0 senza distacco né crash.
 */
class NavClusterService : Service(), LocationListener {

    companion object {
        private const val TAG = "BydNavClusterService"
        private const val CHANNEL_ID = "byd_nav_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "byd_cluster_prefs"
        private const val KEY_PREFERRED_MODE = "preferred_mode"
        const val KEY_FLOATING_PILL_ENABLED = "pref_floating_pill_enabled"

        var instance: NavClusterService? = null
            private set

        const val ACTION_START = "com.byd.clusternavbridge.action.START"
        const val ACTION_STOP = "com.byd.clusternavbridge.action.STOP"
        const val ACTION_SET_MODE = "com.byd.clusternavbridge.action.SET_MODE"
        const val EXTRA_MODE = "extra_mode"

        const val ACTION_ENABLE_TBT = "com.byd.clusternavbridge.action.ENABLE_TBT"
        const val ACTION_DISABLE_TBT = "com.byd.clusternavbridge.action.DISABLE_TBT"
        const val ACTION_TEST_TBT = "com.byd.clusternavbridge.action.TEST_TBT"
        const val ACTION_TOGGLE_VOICE_MUTE = "com.byd.clusternavbridge.action.TOGGLE_VOICE_MUTE"
        const val ACTION_TEST_VOICE = "com.byd.clusternavbridge.action.TEST_VOICE"

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
    private lateinit var voiceManager: VoiceGuidanceManager
    private var locationManager: LocationManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingPill: FloatingPillView? = null
    private var clusterPresentation: ClusterPresentation? = null

    var lastKnownLocation: Location? = null
        private set

    var currentNavState: NavState = NavState()
        private set

    var currentMode: Int = ClusterCommBridge.MODE_SEMPLICE
        private set

    var navStateListener: ((NavState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "NavClusterService onCreate (v1.5.9 Multi-Modale QNX Proto + OSRM Route + 3D Cursor)")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentMode = prefs.getInt(KEY_PREFERRED_MODE, ClusterCommBridge.MODE_SEMPLICE)

        clusterBridge = ClusterCommBridge(this)
        clusterBridge.bindDirectService()

        voiceManager = VoiceGuidanceManager(this) { isMuted ->
            mainHandler.post {
                floatingPill?.updateVoiceMute(isMuted)
            }
        }

        powerMonitor = VehiclePowerMonitor(this) { isPowered ->
            Log.i(TAG, "Vehicle power state updated: isPowered=$isPowered")
            mainHandler.post {
                if (!isPowered) {
                    closeClusterPresentation()
                    clusterBridge.switchClusterMode(ClusterCommBridge.MODE_OFF)
                    voiceManager.stop()
                    floatingPill?.hide()
                } else {
                    initFloatingPill()
                    if (currentMode != ClusterCommBridge.MODE_OFF) {
                        setMode(currentMode)
                    }
                }
            }
        }
        powerMonitor.start()

        // Imposta instance SOLO dopo che clusterBridge, voiceManager e powerMonitor sono inizializzati!
        instance = this

        createNotificationChannel()
        val notification = buildForegroundNotification("Navigazione Cluster DiLink attiva")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        ensureAccessibilityServiceEnabled()
        startContinuousLocationTracking()

        initFloatingPill()
        if (currentMode != ClusterCommBridge.MODE_OFF) {
            mainHandler.post { setMode(currentMode) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        clusterBridge.bindDirectService()
        ensureAccessibilityServiceEnabled()
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
            ACTION_TOGGLE_VOICE_MUTE -> {
                voiceManager.toggleMute()
            }
            ACTION_TEST_VOICE -> {
                Log.w(TAG, "Ricevuto ACTION_TEST_VOICE! Invocazione speak()")
                voiceManager.speak("Indicazioni vocali BYD attive. Tra 300 metri svolta a destra.", force = true)
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

    @Suppress("MissingPermission")
    private fun startContinuousLocationTracking() {
        try {
            val hasFine = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                Log.w(TAG, "Location permissions not yet granted for NavClusterService")
                return
            }

            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val lm = locationManager ?: return

            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 800L, 0.5f, this, Looper.getMainLooper())
                        Log.i(TAG, "Registered continuous location updates on provider: $provider")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register provider $provider: ${e.message}")
                }
            }

            var bestLoc: Location? = null
            for (provider in lm.getProviders(true)) {
                try {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (bestLoc == null || loc.time > bestLoc.time) {
                        bestLoc = loc
                    }
                } catch (_: Exception) {}
            }
            bestLoc?.let { onLocationChanged(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error in startContinuousLocationTracking: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location
        clusterPresentation?.onLocationChanged(location)
        if (currentMode == ClusterCommBridge.MODE_SEMPLICE &&
            (currentNavState.isActive || currentNavState.instruction.isNotBlank())
        ) {
            com.byd.clusternavbridge.route.RouteFetcher.fetchRouteIfNeeded(
                currentLat = location.latitude,
                currentLon = location.longitude,
                destination = currentNavState.destination,
                instruction = currentNavState.instruction
            ) {
                clusterBridge.sendTbtInfo(getEffectiveSimpleTbtState())
            }
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun ensureAccessibilityServiceEnabled() {
        try {
            val component = "${packageName}/${NavAccessibilityService::class.java.name}"
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!current.contains(component)) {
                val updated = if (current.isBlank()) component else "$current:$component"
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.w(TAG, "Auto-enabled NavAccessibilityService in Settings.Secure: $updated")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not auto-enable NavAccessibilityService: ${e.message}")
        }
    }

    fun setMode(mode: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setMode(mode) }
            return
        }
        Log.w(TAG, "Setting mode to $mode")
        currentMode = mode
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFERRED_MODE, mode)
            .apply()

        when (mode) {
            ClusterCommBridge.MODE_PICCOLO -> {
                openClusterPresentation()
                clusterPresentation?.updateMode(mode)
                clusterBridge.switchClusterMode(mode)
                mainHandler.postDelayed({
                    if (currentMode == ClusterCommBridge.MODE_PICCOLO) {
                        clusterBridge.sendNaviPresentationState(1, ClusterCommBridge.MAP_STATE_SMALL_OPEN)
                    }
                }, 180L)
            }
            ClusterCommBridge.MODE_GRANDE -> {
                openClusterPresentation()
                clusterPresentation?.updateMode(mode)
                clusterBridge.switchClusterMode(mode)
                mainHandler.postDelayed({
                    if (currentMode == ClusterCommBridge.MODE_GRANDE) {
                        clusterBridge.sendNaviPresentationState(1, ClusterCommBridge.MAP_STATE_FULL_OPEN)
                    }
                }, 180L)
            }
            ClusterCommBridge.MODE_SEMPLICE -> {
                // In modalità Semplice manteniamo la Presentation HDMI su Display 1
                // a zero carico GPU (mappa GONE) con Card HUD attiva su sfondo OLED nero puro,
                // inviando parallelamente i pacchetti Protobuf nativi QNX (display_mode=2, MAP_STATE_SIMPLE_OPEN=7).
                openClusterPresentation()
                clusterPresentation?.updateMode(mode)
                clusterBridge.switchClusterMode(mode)
                val simpleState = getEffectiveSimpleTbtState()
                clusterBridge.sendTbtInfo(simpleState)
                mainHandler.postDelayed({
                    if (currentMode == ClusterCommBridge.MODE_SEMPLICE) {
                        clusterBridge.sendNaviState(1, ClusterCommBridge.NAVI_STATE_START)
                        clusterBridge.sendNaviPresentationState(1, ClusterCommBridge.MAP_STATE_SIMPLE_OPEN)
                        clusterBridge.sendTbtInfo(getEffectiveSimpleTbtState())
                    }
                }, 220L)
                mainHandler.postDelayed({
                    if (currentMode == ClusterCommBridge.MODE_SEMPLICE) {
                        clusterBridge.sendTbtInfo(getEffectiveSimpleTbtState())
                    }
                }, 700L)
            }
            ClusterCommBridge.MODE_OFF -> {
                closeClusterPresentation()
                clusterBridge.switchClusterMode(mode)
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
            lastKnownLocation?.let { clusterPresentation?.onLocationChanged(it) }
            return
        }

        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val allDisplays = dm.displays
        val presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        // Ricerca display con priorità ai container condivisi OEM di Sealion 7 / DiLink
        val targetDisplay = allDisplays.firstOrNull { it.name.equals("shared_fission_bg_XDJAScreenProjection_0", ignoreCase = true) }
            ?: allDisplays.firstOrNull { it.name.equals("shared_fission_bg_XDJAScreenProjection_1", ignoreCase = true) }
            ?: allDisplays.firstOrNull { it.name.contains("XDJAScreenProjection", ignoreCase = true) }
            ?: presentationDisplays.firstOrNull()
            ?: allDisplays.firstOrNull { it.displayId == 1 }

        if (targetDisplay != null) {
            try {
                clusterPresentation = ClusterPresentation(this, targetDisplay).apply {
                    updateMode(currentMode)
                    show()
                    updateNavState(currentNavState)
                    lastKnownLocation?.let { onLocationChanged(it) }
                }
                Log.i(TAG, "ClusterPresentation opened on display ${targetDisplay.displayId} (${targetDisplay.name}) with mode $currentMode")
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
        Log.w(TAG, "onNavStateChanged: active=${state.isActive}, mode=$currentMode, instr='${state.instruction}', dist='${state.distance}', dest='${state.destination}'")

        if (state.isActive) {
            val targetMode = if (currentMode == ClusterCommBridge.MODE_OFF) ClusterCommBridge.MODE_SEMPLICE else currentMode
            if (clusterBridge.currentMode == ClusterCommBridge.MODE_OFF || currentMode == ClusterCommBridge.MODE_OFF) {
                clusterBridge.switchClusterMode(targetMode)
                mainHandler.post { setMode(targetMode) }
            } else if (targetMode in listOf(ClusterCommBridge.MODE_PICCOLO, ClusterCommBridge.MODE_GRANDE) &&
                (clusterPresentation == null || clusterPresentation?.isShowing != true)
            ) {
                mainHandler.post { setMode(targetMode) }
            }
            if (targetMode == ClusterCommBridge.MODE_SEMPLICE) {
                clusterBridge.sendNaviState(1, ClusterCommBridge.NAVI_STATE_START)
                clusterBridge.sendNaviPresentationState(1, ClusterCommBridge.MAP_STATE_SIMPLE_OPEN)
                clusterBridge.sendTbtInfo(getEffectiveSimpleTbtState())
            } else {
                clusterBridge.sendTbtInfo(state)
            }
            mainHandler.post { clusterPresentation?.updateNavState(state) }
            if (state.instruction.isNotEmpty() || state.destination.isNotEmpty()) {
                voiceManager.onNavInstruction(state.instruction, state.distance, state.destination)
            }
        } else {
            // Non sovrascrivere currentMode scelto dall'utente!
            voiceManager.stop()
            if (currentMode == ClusterCommBridge.MODE_SEMPLICE) {
                // In modalità Semplice manteniamo l'indicatore QNX visibile in stato pronto anziché "Naviga prima"
                clusterBridge.sendNaviState(1, ClusterCommBridge.NAVI_STATE_START)
                clusterBridge.sendNaviPresentationState(1, ClusterCommBridge.MAP_STATE_SIMPLE_OPEN)
                clusterBridge.sendTbtInfo(getEffectiveSimpleTbtState())
            } else {
                clusterBridge.stopTbtMode()
            }
            mainHandler.post {
                clusterPresentation?.updateNavState(state)
            }
        }

        mainHandler.post { floatingPill?.updateNavState(state) }
        mainHandler.post { navStateListener?.invoke(state) }
    }

    private fun getEffectiveSimpleTbtState(): NavState {
        return if (currentNavState.isActive || currentNavState.distance.isNotBlank() || currentNavState.instruction.isNotBlank()) {
            currentNavState.copy(
                isActive = true,
                distance = currentNavState.distance.ifBlank { "100 m" },
                instruction = currentNavState.instruction.ifBlank { "Prosegui sul percorso" },
                remainingTimeDistance = currentNavState.remainingTimeDistance.ifBlank { "1 min • 1.0 km" }
            )
        } else {
            NavState(
                isActive = true,
                sourcePackage = packageName,
                sourceName = "NavBridge",
                distance = "0 m",
                instruction = "In attesa di indicazioni...",
                destination = "NavBridge Pronto",
                eta = "",
                remainingTimeDistance = "1 min • 0.1 km"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "NavClusterService onDestroy")
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        voiceManager.shutdown()
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pillEnabled = prefs.getBoolean(KEY_FLOATING_PILL_ENABLED, false) // Default: SPENTO!

        if (Settings.canDrawOverlays(this)) {
            if (floatingPill == null) {
                floatingPill = FloatingPillView(
                    context = this,
                    onModeSelected = { mode ->
                        setMode(mode)
                    },
                    onToggleVoice = {
                        toggleVoiceMute()
                    }
                )
            }
            if (pillEnabled) {
                floatingPill?.show()
                floatingPill?.updateMode(currentMode)
                floatingPill?.updateVoiceMute(voiceManager.isMuted)
            } else {
                floatingPill?.hide()
            }
        } else {
            Log.w(TAG, "Overlay permission not granted; floating pill deferred")
        }
    }

    fun toggleVoiceMute(): Boolean {
        val muted = voiceManager.toggleMute()
        floatingPill?.updateVoiceMute(muted)
        return muted
    }

    fun toggleFloatingPill(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isShowing = floatingPill?.isShowing == true
        val newState = !isShowing
        prefs.edit().putBoolean(KEY_FLOATING_PILL_ENABLED, newState).apply()

        if (floatingPill == null) {
            initFloatingPill()
        }
        if (newState) {
            floatingPill?.show()
            floatingPill?.updateMode(currentMode)
            floatingPill?.updateVoiceMute(voiceManager.isMuted)
        } else {
            floatingPill?.hide()
        }
        return newState
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
            .setContentTitle("BYD Cluster Nav Bridge v${com.byd.clusternavbridge.BuildConfig.VERSION_NAME}")
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
