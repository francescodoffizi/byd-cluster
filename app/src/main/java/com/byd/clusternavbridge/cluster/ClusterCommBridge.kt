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

class ClusterCommBridge(private val context: Context) {

    companion object {
        private const val TAG = "BydClusterComm"

        const val SETTING_NAVI_DISPLAY_MODE = "moet_extension_navi_display_mode"
        const val SETTING_NAVI_FOCUS_MAP = "moet_extension_navi_focus_map"

        // Canonical QNX cluster modes on BYD Sealion 7:
        // 0: Off / Standard OEM gauges
        // 1: Piccolo (Org / Centro - Navigation map in center box)
        // 2: Semplice (Sport / Minimal dashboard)
        // 3: Grande (Full / Full-Screen map across cluster)
        const val MODE_OFF = 0
        const val MODE_PICCOLO = 1
        const val MODE_SEMPLICE = 2
        const val MODE_GRANDE = 3

        const val CMD_NAVI = 16
        const val DATA_TYPE_NAVI_TBT_INFO = 32
        const val ACTION_CLUSTER_TEST = "clusterTest"

        private const val DESCRIPTOR = "com.ts.lib.cluster.common.IClusterCommService"
        private const val TRANSACTION_SEND_COMMAND = 1
    }

    private var clusterServiceBinder: IBinder? = null
    private var currentMode: Int = MODE_PICCOLO
    private var isFocused: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to ClusterCoreService binder")
            clusterServiceBinder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from ClusterCoreService")
            clusterServiceBinder = null
        }
    }

    init {
        bindClusterService()
    }

    fun bindClusterService() {
        try {
            val intent = Intent().apply {
                component = ComponentName("com.ts.appservice.cluster", "com.ts.appservice.cluster.service.ClusterCoreService")
            }
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "Binding to ClusterCoreService initiated")
        } catch (e: Exception) {
            Log.e(TAG, "bindClusterService error: ${e.message}")
        }
    }

    fun unbindClusterService() {
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            // Ignored
        } finally {
            clusterServiceBinder = null
        }
    }

    @Synchronized
    fun setDisplayMode(mode: Int): Boolean {
        currentMode = mode
        return try {
            val success = Settings.Global.putInt(
                context.contentResolver,
                SETTING_NAVI_DISPLAY_MODE,
                mode
            )
            Log.i(TAG, "Settings.Global $SETTING_NAVI_DISPLAY_MODE = $mode -> $success")

            val bundle = Bundle().apply {
                putInt("DataType", 30)
                putInt("mode", mode)
                putInt("KEY_MSG_TYPE", 3)
                putInt("KEY_SUB_TYPE", 3)
            }
            sendClusterCommand(CMD_NAVI, bundle)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set display mode $mode: ${e.message}")
            false
        }
    }

    @Synchronized
    fun setFocus(focus: Boolean): Boolean {
        isFocused = focus
        val value = if (focus) 1 else 0
        return try {
            val success = Settings.Global.putInt(
                context.contentResolver,
                SETTING_NAVI_FOCUS_MAP,
                value
            )
            Log.i(TAG, "Settings.Global $SETTING_NAVI_FOCUS_MAP = $value -> $success")

            val bundle = Bundle().apply {
                putInt("DataType", if (focus) 33 else 34)
                putInt("mapType", if (focus) 1 else 0)
                putInt("KEY_MSG_TYPE", 3)
                putInt("KEY_SUB_TYPE", 14)
            }
            sendClusterCommand(CMD_NAVI, bundle)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set map focus $value: ${e.message}")
            false
        }
    }

    fun sendTbtInfo(state: NavState) {
        if (!state.isActive) return

        try {
            val distanceMeters = parseDistanceToMeters(state.distance)
            val iconId = mapInstructionToIcon(state.instruction)

            val bundle = Bundle().apply {
                putInt("DataType", DATA_TYPE_NAVI_TBT_INFO)
                putInt("NaviSource", 1)
                putInt("NaviIconTbt", iconId)
                putInt("NaviNextRoadDistance", distanceMeters)
                putString("NaviNextRoadName", state.instruction)
                putInt("NaviRoadType", 0)
                putInt("NaviCrossingStatus", 0)
                putInt("NaviNextRoadPrecent", 0)
                putInt("NaviState", 1)
            }
            sendClusterCommand(CMD_NAVI, bundle)

            // Broadcast fallback for ClusterBroadcastReceiver
            val testIntent = Intent(ACTION_CLUSTER_TEST).apply {
                setPackage("com.ts.appservice.cluster")
                putExtra("commandId", CMD_NAVI)
                putExtra("DataType", 16)
                putExtra("NaviSource", 1)
                putExtra("NaviIconTbt", iconId)
                putExtra("NaviNextRoadDistance", distanceMeters)
                putExtra("NaviNextRoadName", state.instruction)
            }
            context.sendBroadcast(testIntent)

            Log.d(TAG, "TBT info sent: icon=$iconId, dist=${distanceMeters}m, road='${state.instruction}'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch TBT: ${e.message}")
        }
    }

    private fun sendClusterCommand(commandId: Int, bundle: Bundle): Boolean {
        val binder = clusterServiceBinder ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(commandId)
            data.writeInt(1)
            bundle.writeToParcel(data, 0)
            binder.transact(TRANSACTION_SEND_COMMAND, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendClusterCommand transact failed: ${e.message}")
            false
        } finally {
            data.recycle()
        }
    }

    private fun parseDistanceToMeters(distStr: String): Int {
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

    fun mapInstructionToIcon(instr: String): Int {
        val lower = instr.lowercase()
        return when {
            lower.contains("destra") || lower.contains("right") -> 3
            lower.contains("sinistra") || lower.contains("left") -> 2
            lower.contains("rotonda") || lower.contains("rotatoria") || lower.contains("roundabout") -> 11
            lower.contains("inversione") || lower.contains("u-turn") -> 6
            lower.contains("uscita") || lower.contains("exit") -> 8
            lower.contains("biforcazione") || lower.contains("fork") -> 7
            else -> 1 // Straight / Forward
        }
    }

    fun getCurrentMode(): Int = currentMode
    fun isMapFocused(): Boolean = isFocused
}
