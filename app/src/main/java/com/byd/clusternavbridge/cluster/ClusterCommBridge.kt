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
import java.util.concurrent.ConcurrentLinkedQueue

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

        // Modalità Quadro Strumenti DiLink (allineate al reverse engineering OEM Neusoft BydMapLauncher)
        const val MODE_OFF = 1       // MAP_STATE_NAVI_CLOSE (Cruscotto standard tachimetro)
        const val MODE_SEMPLICE = 2  // MAP_STATE_SIMPLE_OPEN (Indicatore sportivo TBT QNX frecce vettoriali)
        const val MODE_PICCOLO = 3   // MAP_STATE_SMALL_OPEN (Mappa 3D nel riquadro centrale tra i quadranti)
        const val MODE_GRANDE = 4    // MAP_STATE_FULL_OPEN (Mappa 3D a schermo intero panoramico)
        const val MODE_INTERO = 4    // Alias canonico DiLink OEM (Intero)

        const val CMD_NAVI = 16

        // Data types ufficiali da ClusterCommService / SomeIpClusterAdapter
        const val DATA_TYPE_NAVI_SWITCH = 15
        const val DATA_TYPE_NAVI_INFO = 16
        const val DATA_TYPE_NAVI_SEND_DISPLAY_MODE = 30
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
    private val pendingCommands = ConcurrentLinkedQueue<Pair<Int, Bundle>>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.w(TAG, "Direct connection to ClusterCoreService established (Binder: $service)")
            directBinder = service
            isBound = true
            try {
                service?.linkToDeath({
                    Log.w(TAG, "ClusterCoreService binder died, attempting reconnect")
                    directBinder = null
                    isBound = false
                    bindDirectService()
                }, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to linkToDeath on service: ${e.message}")
            }
            flushPendingCommands()
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
            Log.w(TAG, "bindDirectService to $SERVICE_CLS initiated: $bound")
        } catch (e: Exception) {
            Log.w(TAG, "bindDirectService error: ${e.message}")
        }
    }

    private fun flushPendingCommands() {
        val binder = directBinder ?: return
        var count = 0
        while (pendingCommands.isNotEmpty()) {
            val (commandId, bundle) = pendingCommands.poll() ?: break
            dispatchCommandDirect(binder, commandId, bundle)
            count++
        }
        if (count > 0) {
            Log.w(TAG, "Flushed $count pending cluster commands after service connection")
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
        Log.w(TAG, "switchClusterMode -> from $currentMode to $mode")
        currentMode = mode
        when (mode) {
            MODE_PICCOLO -> {
                isNavActive = true
                applyDisplayModeSettings(3, focusMap = 1)
                sendDisplayMode(3)
                requestMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_SMALL_OPEN)
                startTbtMode()
                return true
            }
            MODE_GRANDE, MODE_INTERO -> {
                isNavActive = true
                applyDisplayModeSettings(4, focusMap = 1)
                sendDisplayMode(4)
                requestMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_FULL_OPEN)
                startTbtMode()
                return true
            }
            MODE_SEMPLICE -> {
                isNavActive = true
                applyDisplayModeSettings(2, focusMap = 0)
                sendDisplayMode(2)
                abandonMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_SIMPLE_OPEN)
                startTbtMode()
                return true
            }
            MODE_OFF -> {
                isNavActive = false
                applyDisplayModeSettings(1, focusMap = 0)
                sendDisplayMode(1)
                abandonMapFocus(1)
                sendNaviPresentationState(1, MAP_STATE_NAVI_CLOSE)
                stopTbtMode()
                return true
            }
            else -> return false
        }
    }

    /**
     * Invia la modalità di visualizzazione a ClusterCommService (DataType 30)
     */
    fun sendDisplayMode(mode: Int): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 3)
            putInt("DataType", DATA_TYPE_NAVI_SEND_DISPLAY_MODE)
            putInt("mode", mode)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.w(TAG, "sendDisplayMode(mode=$mode) dispatched -> $sent")
        return sent
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
        Log.w(TAG, "requestMapFocus(mapType=$mapType) dispatched -> $sent")
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
        Log.w(TAG, "abandonMapFocus(mapType=$mapType) dispatched -> $sent")
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
        Log.w(TAG, "sendNaviPresentationState(mapType=$mapType, state=$state) dispatched -> $sent")
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
        Log.w(TAG, "startTbtMode dispatched -> $sent")
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
        Log.w(TAG, "stopTbtMode dispatched -> $sent")
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
        val iconId = if (state.instruction.isNotBlank()) {
            mapInstructionToIcon(state.instruction)
        } else {
            1 // Freccia dritto se siamo in navigazione verso destinazione senza svolta immediata
        }
        val remainingSecs = parseEtaToSeconds(state.remainingTimeDistance)
        val totalDistMeters = parseTotalDistanceToMeters(state.remainingTimeDistance).takeIf { it > 0 } ?: distanceMeters

        val roadName = when {
            state.instruction.isNotBlank() -> state.instruction
            state.destination.isNotBlank() -> state.destination
            else -> "Navigazione"
        }

        val bundle = Bundle().apply {
            putInt("DataType", DATA_TYPE_NAVI_INFO)
            putInt("NaviSource", 1)
            putInt("NaviIconTbt", iconId)
            putInt("NaviNextRoadDistance", distanceMeters)
            putString("NaviNextRoadName", roadName)
            putInt("NaviRoadType", 0)
            putInt("NaviCrossingStatus", 0)
            putInt("NaviNextRoadPrecent", 0)
            putInt("NaviDistanceToDest", totalDistMeters)
            putInt("NaviDistanceToDestUnit", if (totalDistMeters >= 1000) 0 else 2) // 0: KM, 2: M
            putInt("NaviToDestTime", remainingSecs)
            putInt("NaviState", 2)
        }

        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.w(TAG, "sendTbtInfo: icon=$iconId, dist=${distanceMeters}m, total=${totalDistMeters}m, road='$roadName' -> $sent")
        return sent
    }

    private fun dispatchCommand(commandId: Int, bundle: Bundle): Boolean {
        val binder = directBinder
        if (binder == null) {
            Log.w(TAG, "dispatchCommand queued (commandId=$commandId): binder not yet connected, queuing")
            pendingCommands.offer(Pair(commandId, Bundle(bundle)))
            bindDirectService()
            return true
        }
        return dispatchCommandDirect(binder, commandId, bundle)
    }

    private fun dispatchCommandDirect(binder: IBinder, commandId: Int, bundle: Bundle): Boolean {
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
            Log.w(TAG, "Settings.Global applied: display_mode=$mode, focus_map=$focusMap")
        } catch (e: SecurityException) {
            Log.e(TAG, "CRITICAL: WRITE_SECURE_SETTINGS missing! Esegui via ADB: 'pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS' per consentire il cambio modalità del cruscotto!")
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

    fun parseTotalDistanceToMeters(str: String): Int {
        if (str.isBlank()) return 0
        return try {
            val distRegex = Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\b").find(str)
            if (distRegex != null) {
                val numStr = distRegex.groupValues[1].replace(",", ".")
                val unit = distRegex.groupValues[2].lowercase()
                val num = numStr.toDoubleOrNull() ?: 0.0
                if (unit == "km") (num * 1000).toInt() else num.toInt()
            } else {
                0
            }
        } catch (_: Exception) {
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
