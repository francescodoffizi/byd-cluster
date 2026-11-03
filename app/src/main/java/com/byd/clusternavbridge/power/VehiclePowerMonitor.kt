package com.byd.clusternavbridge.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Monitors vehicle ignition/ACC power state on BYD DiLink.
 *
 * Strict constraint: Cluster projection and Map rendering MUST NEVER activate
 * when the vehicle is OFF or sleeping to prevent 12V battery drain, sleep aborts
 * and display initialization freezes.
 */
class VehiclePowerMonitor(
    private val context: Context,
    private val onPowerStateChanged: (isPowered: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "VehiclePowerMonitor"

        // BYD OEM and Android power intents
        const val ACTION_ACC_ON = "com.byd.action.ACC_ON"
        const val ACTION_ACC_OFF = "com.byd.action.ACC_OFF"
        const val ACTION_IGN_ON = "com.byd.action.IGN_ON"
        const val ACTION_ACC_MODE_CHANGED = "com.byd.accmode.ACC_MODE_CHANGED"
        const val EXTRA_ACC_MODE = "acc_mode"
    }

    private var isReceiverRegistered = false

    @Volatile
    var isPowered: Boolean = true
        private set

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.i(TAG, "Received power broadcast: $action")
            when (action) {
                ACTION_ACC_ON, ACTION_IGN_ON -> {
                    updatePowerState(true, "ACC/IGN ON broadcast")
                }
                ACTION_ACC_OFF -> {
                    updatePowerState(false, "ACC OFF broadcast")
                }
                ACTION_ACC_MODE_CHANGED -> {
                    val mode = intent.getIntExtra(EXTRA_ACC_MODE, -1)
                    Log.i(TAG, "ACC mode changed: $mode")
                    // Mode 0 = OFF, Mode 1 = ACC, Mode 2 = ON/READY
                    val powered = mode > 0
                    updatePowerState(powered, "ACC_MODE_CHANGED ($mode)")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Check if vehicle is truly off
                    if (!checkInitialPowerState()) {
                        updatePowerState(false, "SCREEN_OFF and vehicle not powered")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    updatePowerState(true, "SCREEN_ON")
                }
            }
        }
    }

    init {
        isPowered = checkInitialPowerState()
        Log.i(TAG, "Initial vehicle power state: $isPowered")
    }

    fun start() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_ACC_ON)
            addAction(ACTION_ACC_OFF)
            addAction(ACTION_IGN_ON)
            addAction(ACTION_ACC_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(powerReceiver, filter)
        isReceiverRegistered = true
        Log.i(TAG, "VehiclePowerMonitor registered")
    }

    fun stop() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering powerReceiver", e)
        }
        isReceiverRegistered = false
    }

    private fun checkInitialPowerState(): Boolean {
        try {
            // Check BYD setting if available
            val accSetting = Settings.Global.getInt(context.contentResolver, "byd_acc_state", -1)
            if (accSetting != -1) {
                return accSetting > 0
            }
        } catch (_: Throwable) {}

        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                return pm.isInteractive
            }
        } catch (_: Throwable) {}

        return true
    }

    private fun updatePowerState(powered: Boolean, reason: String) {
        if (isPowered != powered) {
            isPowered = powered
            Log.i(TAG, "Vehicle power state changed to $powered (reason: $reason)")
            onPowerStateChanged(powered)
        }
    }
}
