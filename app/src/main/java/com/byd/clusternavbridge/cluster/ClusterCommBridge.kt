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

        // Data types ufficiali da ClusterCommService / SomeIpClusterAdapter / InstrumentMediaManagerImpl
        const val DATA_TYPE_NAVI_SWITCH = 15
        const val DATA_TYPE_NAVI_INFO = 16
        const val DATA_TYPE_NAVI_SEND_DISPLAY_MODE = 30
        const val DATA_TYPE_NAVI_PRESENTATION_STATE = 31
        const val DATA_TYPE_NAVI_TBT_INFO = 32
        const val DATA_TYPE_REQUEST_MAP_FOCUS = 33
        const val DATA_TYPE_ABANDON_MAP_FOCUS = 34
        const val DATA_TYPE_NAVI_STATE = 35
        const val DATA_TYPE_NAVI_TBT_STRING_INFO = 37

        // Stati Navigazione QNX (InstrumentMediaManagerImpl.sendNaviState)
        const val NAVI_STATE_START = 0
        const val NAVI_STATE_STOP = 1

        // Unità distanza QNX (InteractionProto$Unit)
        const val UNIT_KM = 1
        const val UNIT_MILES = 2
        const val UNIT_M = 3
        const val UNIT_YARDS = 4
        const val UNIT_FT = 5

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
    private var isBindingInProgress = false
    private val pendingCommands = ConcurrentLinkedQueue<Pair<Int, Bundle>>()

    private val clusterCallback = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) { // TRANSACTION_onReceiveCommand
                try {
                    data.enforceInterface("com.ts.lib.cluster.common.IClusterCommCallback")
                    val bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    Log.d(TAG, "onReceiveCommand from ClusterCoreService: $bundle")
                    return true
                } catch (_: Exception) {}
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.w(TAG, "Direct connection to ClusterCoreService established (Binder: $service)")
            directBinder = service
            isBound = true
            isBindingInProgress = false
            try {
                service?.linkToDeath({
                    Log.w(TAG, "ClusterCoreService binder died, attempting reconnect")
                    directBinder = null
                    isBound = false
                    isBindingInProgress = false
                    bindDirectService()
                }, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to linkToDeath on service: ${e.message}")
            }
            registerClusterCallback()
            flushPendingCommands()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Direct connection to ClusterCoreService lost")
            directBinder = null
            isBound = false
            isBindingInProgress = false
        }
    }

    private fun registerClusterCallback() {
        val binder = directBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(CMD_NAVI)
            data.writeStrongBinder(clusterCallback)
            binder.transact(2, data, reply, 0) // TRANSACTION_registerCallback = 2
            reply.readException()
            val result = reply.readInt()
            Log.w(TAG, "registerClusterCallback(CMD_NAVI) result -> $result")
        } catch (e: Exception) {
            Log.w(TAG, "registerClusterCallback failed: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    init {
        bindDirectService()
    }

    fun bindDirectService() {
        if (directBinder != null || isBindingInProgress) return
        try {
            val intent = Intent().apply {
                component = ComponentName(SERVICE_PKG, SERVICE_CLS)
            }
            isBindingInProgress = true
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                isBindingInProgress = false
            }
            Log.w(TAG, "bindDirectService to $SERVICE_CLS initiated: $bound")
        } catch (e: Exception) {
            isBindingInProgress = false
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
                isBindingInProgress = false
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
                sendDisplayMode(3)
                sendDisplayModeDirectToQnx(3)
                applyDisplayModeSettings(3, focusMap = 1)
                requestMapFocus(1)
                sendMapFocusDirectToQnx(1)
                sendNaviState(1, NAVI_STATE_START)
                sendNaviPresentationState(1, MAP_STATE_SMALL_OPEN)
                startTbtMode()
                return true
            }
            MODE_GRANDE, MODE_INTERO -> {
                isNavActive = true
                sendDisplayMode(4)
                sendDisplayModeDirectToQnx(4)
                applyDisplayModeSettings(4, focusMap = 1)
                requestMapFocus(1)
                sendMapFocusDirectToQnx(1)
                sendNaviState(1, NAVI_STATE_START)
                sendNaviPresentationState(1, MAP_STATE_FULL_OPEN)
                startTbtMode()
                return true
            }
            MODE_SEMPLICE -> {
                isNavActive = true
                // Ripristino dell'indicatore semplificato nativo QNX (display_mode=2, MAP_STATE_SIMPLE_OPEN=7):
                // non apre la finestra nera HDMI di Piccolo (mode 3), ma attiva il widget TBT nativo QNX
                // nel quadrante destro e invia lo stato NAVI_STATE_START (0) + MAP_STATE_SIMPLE_OPEN (7).
                sendDisplayMode(2)
                sendDisplayModeDirectToQnx(2)
                applyDisplayModeSettings(2, focusMap = 1)
                requestMapFocus(1)
                sendMapFocusDirectToQnx(1)
                sendNaviState(1, NAVI_STATE_START)
                sendNaviPresentationState(1, MAP_STATE_SIMPLE_OPEN)
                startTbtMode()
                return true
            }
            MODE_OFF -> {
                isNavActive = false
                sendNaviPresentationState(1, MAP_STATE_NAVI_CLOSE)
                sendNaviState(1, NAVI_STATE_STOP)
                abandonMapFocus(1)
                sendMapFocusDirectToQnx(0)
                sendDisplayMode(1)
                sendDisplayModeDirectToQnx(1)
                applyDisplayModeSettings(1, focusMap = 0)
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
     * Invia direttamente a QNX il comando HotKeySetting (msgType=3, subtype=3) tramite passthrough DataType 31,
     * aggirando la deduplica del ContentObserver in SomeIpClusterAdapter.
     */
    fun sendDisplayModeDirectToQnx(mode: Int): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 3)
            putInt("DataType", DATA_TYPE_NAVI_PRESENTATION_STATE)
            putInt("mode", mode)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.w(TAG, "sendDisplayModeDirectToQnx(mode=$mode) dispatched -> $sent")
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
     * Invia direttamente a QNX il comando MapFocus (msgType=3, subtype=14) tramite passthrough DataType 31,
     * aggirando la deduplica di MapFocusManager quando mapType=1 è già in cima a mFocusedMapList.
     */
    fun sendMapFocusDirectToQnx(mapType: Int = 1): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 14)
            putInt("DataType", DATA_TYPE_NAVI_PRESENTATION_STATE)
            putInt("mapType", mapType)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.w(TAG, "sendMapFocusDirectToQnx(mapType=$mapType) dispatched -> $sent")
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
     * Notifica a QNX lo stato della navigazione (DataType 35, msgType=3, subtype=2 -> NavigationState)
     * naviState: 0 = NAVI_STATE_START, 1 = NAVI_STATE_STOP
     */
    fun sendNaviState(mapType: Int = 1, naviState: Int): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 2)
            putInt("DataType", DATA_TYPE_NAVI_STATE)
            putInt("mapType", mapType)
            putInt("naviState", naviState)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.w(TAG, "sendNaviState(mapType=$mapType, naviState=$naviState) dispatched -> $sent")
        return sent
    }

    /**
     * Notifica a QNX lo stato della presentazione (Small=5, Full=6, Simple=7, Close=1)
     * Nota critica REA: InteractionToBundle.java (qnx.apk) e InstrumentMediaManagerImpl leggono la chiave "state"!
     */
    fun sendNaviPresentationState(mapType: Int = 1, state: Int): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 15)
            putInt("DataType", DATA_TYPE_NAVI_PRESENTATION_STATE)
            putInt("mapType", mapType)
            putInt("state", state)
        }
        val sent = dispatchCommand(CMD_NAVI, bundle)
        Log.w(TAG, "sendNaviPresentationState(mapType=$mapType, state=$state) dispatched -> $sent")
        return sent
    }

    /**
     * Attiva il canale TBT vettoriale a quadro strumenti su QNX (DataType 35)
     */
    fun startTbtMode(): Boolean {
        isNavActive = true
        val sent = sendNaviState(1, NAVI_STATE_START)
        Log.w(TAG, "startTbtMode (DataType 35 NAVI_STATE_START) dispatched -> $sent")
        return sent
    }

    /**
     * Disattiva le frecce a quadro strumenti su QNX (DataType 35)
     */
    fun stopTbtMode(): Boolean {
        isNavActive = false
        val sent = sendNaviState(1, NAVI_STATE_STOP)
        Log.w(TAG, "stopTbtMode (DataType 35 NAVI_STATE_STOP) dispatched -> $sent")
        return sent
    }

    /**
     * Invia le informazioni di svolta nei due formati Protobuf nativi QNX:
     * 1) DataType 32 (msgType=3, subtype=7 -> InteractionProto.Navigation, float distances + Unit enum)
     * 2) DataType 37 (msgType=3, subtype=20 -> InteractionProto.NaviTbtInfo, string distances/units)
     */
    fun sendTbtInfo(state: NavState): Boolean {
        if (!state.isActive) {
            return stopTbtMode()
        }

        if (currentMode == MODE_OFF) {
            switchClusterMode(MODE_SEMPLICE)
        } else if (!isNavActive) {
            startTbtMode()
        }

        val cachedRoute = com.byd.clusternavbridge.route.RouteFetcher.getCachedRoute()
        val rawDistanceMeters = parseDistanceToMeters(state.distance)
        val parsedTotalDist = parseTotalDistanceToMeters(state.remainingTimeDistance)
        val totalDistMeters = when {
            parsedTotalDist > 0 -> parsedTotalDist
            cachedRoute != null && cachedRoute.distanceMeters > 0 -> cachedRoute.distanceMeters.toInt()
            rawDistanceMeters > 0 -> rawDistanceMeters
            else -> 100
        }
        val distanceMeters = if (rawDistanceMeters > 0) rawDistanceMeters else totalDistMeters.takeIf { it > 0 } ?: 100
        val iconId = if (state.instruction.isNotBlank()) {
            mapInstructionToIcon(state.instruction)
        } else {
            9 // 9 = Freccia dritto / continua secondo codifica OEM BYDAutoNaviDevice
        }
        val parsedRemSecs = parseEtaToSeconds(state.remainingTimeDistance)
        val remainingSecs = when {
            parsedRemSecs > 0 -> parsedRemSecs
            cachedRoute != null && cachedRoute.durationSeconds > 0 -> cachedRoute.durationSeconds.toInt()
            else -> 60
        }

        val roadName = when {
            state.instruction.isNotBlank() -> state.instruction
            state.destination.isNotBlank() -> state.destination
            else -> "Prosegui sul percorso"
        }

        val nextRoadDistFloat: Float
        val nextDistUnit: Int
        val nextRoadDistStr: String
        val nextRoadDistUnitStr: String
        if (distanceMeters >= 1000) {
            nextRoadDistFloat = distanceMeters / 1000f
            nextDistUnit = UNIT_KM
            nextRoadDistStr = String.format(java.util.Locale.US, "%.1f", nextRoadDistFloat)
            nextRoadDistUnitStr = "km"
        } else {
            nextRoadDistFloat = distanceMeters.toFloat()
            nextDistUnit = UNIT_M
            nextRoadDistStr = distanceMeters.toString()
            nextRoadDistUnitStr = "m"
        }

        val distToDestFloat: Float
        val distToDestUnit: Int
        val distToDestStr: String
        val distToDestUnitStr: String
        if (totalDistMeters >= 1000) {
            distToDestFloat = totalDistMeters / 1000f
            distToDestUnit = UNIT_KM
            distToDestStr = String.format(java.util.Locale.US, "%.1f", distToDestFloat)
            distToDestUnitStr = "km"
        } else {
            distToDestFloat = totalDistMeters.toFloat()
            distToDestUnit = UNIT_M
            distToDestStr = totalDistMeters.toString()
            distToDestUnitStr = "m"
        }

        val totalMinutes = (remainingSecs / 60).coerceAtLeast(1)
        val toDestDays = totalMinutes / (24 * 60)
        val effectiveEta = state.eta.ifBlank {
            cachedRoute?.formattedEta ?: run {
                val futureMillis = System.currentTimeMillis() + (remainingSecs * 1000L)
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(futureMillis))
            }
        }
        val etaParts = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(effectiveEta)
        // Nota REA: InteractionToBundle case 7 mappa toDestHours -> arriveTimeHour (0..23) e toDestMinutes -> arriveTimeMinute (0..59)
        val toDestHours = etaParts?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23)
            ?: ((totalMinutes % (24 * 60)) / 60).coerceIn(0, 23)
        val toDestMinutes = etaParts?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59)
            ?: (totalMinutes % 60).coerceIn(0, 59)

        // 1. Formato QNX Navigation Protobuf (DataType 32, msgType=3, subtype=7)
        val protoNavBundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 7)
            putInt("DataType", DATA_TYPE_NAVI_TBT_INFO)
            putInt("mapType", 1)
            putInt("nextTbtIcon", iconId)
            putString("nextRoadName", roadName)
            putFloat("nextRoadDist", nextRoadDistFloat)
            putInt("nextDistUnit", nextDistUnit)
            putInt("nextNextTbtIcon", 0)
            putFloat("nextNextRoadDist", 0f)
            putInt("nextNextDistUnit", UNIT_M)
            putFloat("distToDest", distToDestFloat)
            putInt("distToDestUnit", distToDestUnit)
            putInt("toDestTime", remainingSecs)
            putInt("toDestDays", toDestDays)
            putInt("toDestHours", toDestHours)
            putInt("toDestMinutes", toDestMinutes)
        }
        val sent32 = dispatchCommand(CMD_NAVI, protoNavBundle)

        // 2. Formato QNX NaviTbtInfo String Protobuf (DataType 37, msgType=3, subtype=20)
        val protoStringBundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("subtype", 20)
            putInt("DataType", DATA_TYPE_NAVI_TBT_STRING_INFO)
            putInt("mapType", 1)
            putInt("nextTbtIcon", iconId)
            putString("nextRoadName", roadName)
            putString("nextRoadDistData", nextRoadDistStr)
            putString("nextRoadDistUnit", nextRoadDistUnitStr)
            putInt("nextNextTbtIcon", 0)
            putString("nextNextDistData", "")
            putString("nextNextDistAndUnit", "")
            putString("distToDestDisData", distToDestStr)
            putString("distToDestDisUnit", distToDestUnitStr)
            putString("distToDestTime", "$totalMinutes min")
            putString("arriveToDestTimeDay", "")
            putString("arriveToDestTime", effectiveEta)
            putString("multipleText", "")
            putString("multipleText1", "")
            putString("multipleText2", "")
        }
        val sent37 = dispatchCommand(CMD_NAVI, protoStringBundle)

        Log.w(TAG, "sendTbtInfo(QNX 32+37): icon=$iconId, dist=$nextRoadDistStr$nextRoadDistUnitStr, total=$distToDestStr$distToDestUnitStr, road='$roadName' -> sent32=$sent32, sent37=$sent37")
        return sent32 && sent37
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
            lower.contains("inversione") || lower.contains("u-turn") -> 8
            lower.contains("rotonda") || lower.contains("rotatoria") || lower.contains("roundabout") -> 16
            lower.contains("destinazione") || lower.contains("arrivato") || lower.contains("destination") -> 15
            lower.contains("destra") || lower.contains("right") -> {
                when {
                    lower.contains("legger") || lower.contains("slight") || lower.contains("tieni") || lower.contains("keep") -> 5
                    lower.contains("stretto") || lower.contains("sharp") || lower.contains("hard") || lower.contains("gomito") -> 7
                    else -> 3
                }
            }
            lower.contains("sinistra") || lower.contains("left") -> {
                when {
                    lower.contains("legger") || lower.contains("slight") || lower.contains("tieni") || lower.contains("keep") -> 4
                    lower.contains("stretto") || lower.contains("sharp") || lower.contains("hard") || lower.contains("gomito") -> 6
                    else -> 2
                }
            }
            lower.contains("uscita") || lower.contains("exit") -> 5
            lower.contains("biforcazione") || lower.contains("fork") -> 5
            else -> 9 // 9 = Dritto / Prosegui (OEM BYDAutoNaviDevice)
        }
    }
}
