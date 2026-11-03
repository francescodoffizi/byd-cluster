package com.byd.clusternavbridge.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.ui.ClusterPresentation

class ClusterCommBridge(private val context: Context) {

    companion object {
        private const val TAG = "BydClusterComm"

        const val SETTING_NAVI_DISPLAY_MODE = "moet_extension_navi_display_mode"
        const val SETTING_NAVI_FOCUS_MAP = "moet_extension_navi_focus_map"

        const val MODE_DEFAULT = 0
        const val MODE_MINI_MAP = 2
        const val MODE_FULLSCREEN_MAP = 3
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var presentation: ClusterPresentation? = null
    private var currentMode: Int = MODE_MINI_MAP
    private var isFocused: Boolean = false

    fun getClusterDisplay(): Display? {
        val displays = displayManager.displays
        for (d in displays) {
            if (d.displayId == 1 || d.name.contains("HDMI", ignoreCase = true)) {
                return d
            }
        }
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (presentationDisplays.isNotEmpty()) {
            return presentationDisplays[0]
        }
        return null
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
            Log.i(TAG, "Settings.Global putInt $SETTING_NAVI_DISPLAY_MODE = $mode -> $success")
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
            Log.i(TAG, "Settings.Global putInt $SETTING_NAVI_FOCUS_MAP = $value -> $success")
            
            if (focus) {
                ensurePresentationShowing()
            } else {
                dismissPresentation()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set map focus $value: ${e.message}")
            false
        }
    }

    fun updatePresentation(navState: NavState) {
        try {
            if (isFocused) {
                ensurePresentationShowing()
                presentation?.updateNav(navState)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating presentation: ${e.message}")
        }
    }

    private fun ensurePresentationShowing() {
        val display = getClusterDisplay() ?: return
        if (presentation == null) {
            try {
                presentation = ClusterPresentation(context, display)
                presentation?.show()
                Log.i(TAG, "ClusterPresentation created and shown on display ${display.displayId} (${display.name})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show presentation: ${e.message}")
                presentation = null
            }
        }
    }

    private fun dismissPresentation() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss presentation: ${e.message}")
        } finally {
            presentation = null
        }
    }

    fun getCurrentMode(): Int = currentMode
    fun isMapFocused(): Boolean = isFocused
}
