package com.byd.clusternavbridge.cluster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import com.byd.clusternavbridge.model.NavState

/**
 * ClusterCommBridge v1.4.0 - Supporto Completo Multi-Modale (Semplice, Piccolo, Grande)
 *
 * Implementa l'IPC nativo DiLink verso com.ts.appservice.cluster.service.ClusterCoreService
 * conforme al reverse engineering di Neusoft (BydMapLauncher) e BYDAutoInstrumentDevice.
 */
class ClusterCommBridge(private val context: Context) {

    companion object {
        private const val TAG = "BydClusterComm"

        const val SETTING_NAVI_DISPLAY_MODE = "moet_extension_navi_display_mode"
        const val SETTING_NAVI_FOCUS_MAP = "moet_extension_navi_focus_map"

        // Modalità Quadro Strumenti DiLink
        const val MODE_OFF = 0
        const val MODE_PICCOLO = 1  // Mappa 3D nel riquadro centrale tra i quadranti
        const val MODE_SEMPLICE = 2  // Indicatore sportivo frecce TBT vettoriali QNX
        const val MODE_GRANDE = 3    // Mappa 3D a schermo intero panoramico

        const val CMD_NAVI = 16

        // Data types ufficiali da ClusterCommService / SomeIpClusterAdapter
        const val DATA_TYPE_NAVI_SWITCH = 15
        const val DATA_TYPE_NAVI_INFO = 16
        const val DATA_TYPE_NAVI_PRESENTATION_STATE = 31
        const val DATA_TYPE_REQUEST_MAP_FOCUS = 33
        const val DATA_TYPE_ABANDON_MAP_FOCUS = 34

        // Stati Mappa Presentation ufficiali QNX (BYDAutoInstrumentDevice)
        const val MAP_STATE_NAVI_CLOSE = 1
        const val MAP_STATE_SMALL_CLOSE = 2
        const val MAP_STATE_FULL_CLOSE = 3
        const val MAP_STATE_SIMPLE_CLOSE = 4
        const val MAP_STATE_SMALL_OPEN = 5
        const val MAP_STATE_FULL_OPEN = 6
        const val MAP_STATE_SIMPLE_OPEN = 7

        private const val DESCRIPTOR = "com.ts.lib.cluster.common.IClusterCommService"
        private const val TRANSACTION_SEND_COMMAND = 1
        private const val SERVICE_PKG = "com.ts.appservice.cluster"
        private const val SERVICE_CLS = "com.ts.appservice.cluster.service.ClusterCoreService"
    }

    private var directBinder: IBinder? = null
    var currentMode: Int = MODE_OFF
        private set
    private var isNavActive = false
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Direct connection to ClusterCoreService established (Binder: $service)")
            directBinder = service
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Direct connection to ClusterCoreService lost")
            directBinder = null
            isBound = false
        }
    }

    init {
        bindDirectService()
    }

    fun bindDirectService() {
        if (directBinder != null) return
        try {
            val intent = Intent().apply {
                component = ComponentName(SERVICE_PKG, SERVICE_CLS)
            }
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindDirectService to $SERVICE_CLS initiated: $bound")
        } catch (e: Exception) {
            Log.w(TAG, "bindDirectService error: ${e.message}")
        }
    }

    fun unbindService() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            } finally {
                directBinder = null
                isBound = false
            }
        }
    }

    /**
     * Commuta la modalità del cruscotto eseguendo l'handshake completo con QNX
     */
    fun switchClusterMode(mode: Int): Boolean {
        Log.i(TAG, "switchClusterMode -> from $currentMode to $mode")
        currentMode = mode
        when (mode) {
            MODE_PICCOLO -> {
                isNavActive = true
                applyDisplayModeSettings(MODE_PICCOLO, focusMap = 1)
                requestMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_SMALL_OPEN)
                startTbtMode()
                return true
            }
            MODE_GRANDE -> {
                isNavActive = true
                applyDisplayModeSettings(MODE_GRANDE, focusMap = 1)
                requestMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_FULL_OPEN)
                startTbtMode()
                return true
            }
            MODE_SEMPLICE -> {
                isNavActive = true
                applyDisplayModeSettings(MODE_SEMPLICE, focusMap = 0)
                abandonMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_SIMPLE_OPEN)
                startTbtMode()
                return true
            }
            MODE_OFF -> {
                isNavActive = false
                applyDisplayModeSettings(MODE_OFF, focusMap = 0)
                abandonMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_NAVI_CLOSE)
                stopTbtMode()
                return true
            }
            else -> return false
        }
    }

    /**
     * Richiede a QNX di abilitare il routing del feed video Display 1 (HDMI)
     */
    fun requestMapFocus(mapType: Int = 1): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("DataType", DATA_TYPE_REQUEST_MAP_FOCUS)
            putInt("mapType", mapType)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.i(TAG, "requestMapFocus(mapType=$mapType) dispatched -> $sent")
        return sent
    }

    /**
     * Rilascia il focus video HDMI e ripristina il feed strumenti nativo QNX
     */
    fun abandonMapFocus(mapType: Int = 1): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("DataType", DATA_TYPE_ABANDON_MAP_FOCUS)
            putInt("mapType", mapType)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.i(TAG, "abandonMapFocus(mapType=$mapType) dispatched -> $sent")
        return sent
    }

    /**
     * Notifica a QNX lo stato della presentazione (Small, Full, Simple, Close)
     */
    fun sendNaviPresentationState(mapType: Int = 1, state: Int): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 15)
            putInt("DataType", DATA_TYPE_NAVI_PRESENTATION_STATE)
            putInt("mapType", mapType)
            putInt("naviPresentationState", state)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.i(TAG, "sendNaviPresentationState(mapType=$mapType, state=$state) dispatched -> $sent")
        return sent
    }

    /**
     * Attiva il canale TBT vettoriale a quadro strumenti
     */
    fun startTbtMode(): Boolean {
        val bundle = Bundle().apply {
            putInt("DataType", DATA_TYPE_NAVI_SWITCH)
            putInt("NaviSource", 1) // 1: Local / AMAP compatible
            putInt("NaviTbtSwitch", 1) // 1: OPEN TBT
            putInt("NaviState", 2) // 2: Navigation started
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.i(TAG, "startTbtMode dispatched -> $sent")
        return sent
    }

    /**
     * Disattiva le frecce a quadro strumenti e ripristina la visualizzazione tachimetro
     */
    fun stopTbtMode(): Boolean {
        val bundle = Bundle().apply {
            putInt("DataType", DATA_TYPE_NAVI_SWITCH)
            putInt("NaviSource", 1)
            putInt("NaviTbtSwitch", 0) // 0: CLOSE TBT
            putInt("NaviState", 0) // 0: Not navigating
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.i(TAG, "stopTbtMode dispatched -> $sent")
        return sent
    }

    /**
     * Invia le informazioni di svolta (manovra, metri, via, tempo)
     */
    fun sendTbtInfo(state: NavState): Boolean {
        if (!state.isActive) {
            return stopTbtMode()
        }

        if (!isNavActive) {
            startTbtMode()
        }

        val distanceMeters = parseDistanceToMeters(state.distance)
        val iconId = mapInstructionToIcon(state.instruction)
        val remainingSecs = parseEtaToSeconds(state.remainingTimeDistance)

        val bundle = Bundle().apply {
            putInt("DataType", DATA_TYPE_NAVI_INFO)
            putInt("NaviSource", 1)
            putInt("NaviIconTbt", iconId)
            putInt("NaviNextRoadDistance", distanceMeters)
            putString("NaviNextRoadName", state.instruction)
            putInt("NaviRoadType", 0)
            putInt("NaviCrossingStatus", 0)
            putInt("NaviNextRoadPrecent", 0)
            putInt("NaviDistanceToDest", distanceMeters)
            putInt("NaviDistanceToDestUnit", 0) // 0: KM
            putInt("NaviToDestTime", remainingSecs)
            putInt("NaviState", 2)
        }

        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.d(TAG, "sendTbtInfo: icon=$iconId, dist=${distanceMeters}m, road='${state.instruction}' -> $sent")
        return sent
    }

    private fun dispatchCommand(commandId: Int, bundle: Bundle): Boolean {
        var binder = directBinder
        if (binder == null) {
            bindDirectService()
            binder = directBinder
        }
        if (binder == null) {
            Log.w(TAG, "dispatchCommand deferred: ClusterCoreService binder not yet connected")
            return false
        }

        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(commandId)
            data.writeInt(1) // Bundle presence flag
            bundle.writeToParcel(data, 0)
            binder.transact(TRANSACTION_SEND_COMMAND, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Exception) {
            Log.w(TAG, "directBinder transact failed: ${e.message}")
            false
        } finally {
            data.recycle()
        }
    }

    private fun applyDisplayModeSettings(mode: Int, focusMap: Int) {
        try {
            Settings.Global.putInt(
                context.contentResolver,
                SETTING_NAVI_DISPLAY_MODE,
                mode
            )
            Settings.Global.putInt(
                context.contentResolver,
                SETTING_NAVI_FOCUS_MAP,
                focusMap
            )
            Log.i(TAG, "Settings.Global applied: display_mode=$mode, focus_map=$focusMap")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Settings.Global: ${e.message}")
        }
    }

    fun parseDistanceToMeters(distStr: String): Int {
        if (distStr.isBlank()) return 0
        return try {
            val cleaned = distStr.replace(",", ".").trim()
            val numberPart = cleaned.filter { it.isDigit() || it == '.' }
            val value = numberPart.toDoubleOrNull() ?: return 0
            if (cleaned.contains("km", ignoreCase = true)) {
                (value * 1000).toInt()
            } else {
                value.toInt()
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun parseEtaToSeconds(str: String): Int {
        if (str.isBlank()) return 0
        return try {
            var totalMinutes = 0
            val parts = str.lowercase().split(" ")
            for (i in parts.indices) {
                val num = parts[i].toIntOrNull() ?: continue
                if (i + 1 < parts.size) {
                    when {
                        parts[i + 1].startsWith("h") || parts[i + 1].startsWith("ora") -> totalMinutes += num * 60
                        parts[i + 1].startsWith("min") -> totalMinutes += num
                    }
                }
            }
            totalMinutes * 60
        } catch (e: Exception) {
            0
        }
    }

    fun mapInstructionToIcon(instr: String): Int {
        val lower = instr.lowercase()
        return when {
            lower.contains("destra") || lower.contains("right") -> {
                if (lower.contains("legger") || lower.contains("slight")) 5 else 3
            }
            lower.contains("sinistra") || lower.contains("left") -> {
                if (lower.contains("legger") || lower.contains("slight")) 4 else 2
            }
            lower.contains("rotonda") || lower.contains("rotatoria") || lower.contains("roundabout") -> 11
            lower.contains("inversione") || lower.contains("u-turn") -> 6
            lower.contains("uscita") || lower.contains("exit") -> 8
            lower.contains("biforcazione") || lower.contains("fork") -> 7
            else -> 1 // Dritto / Prosegui
        }
    }
}
