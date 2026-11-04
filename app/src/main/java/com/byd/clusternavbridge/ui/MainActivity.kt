package com.byd.clusternavbridge.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.cluster.ClusterCommBridge
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.service.NavClusterService

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var tvNavState: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantNotification: Button
    private lateinit var btnModePiccolo: Button
    private lateinit var btnModeGrande: Button
    private lateinit var btnModeSemplice: Button
    private lateinit var btnReleaseFocus: Button
    private lateinit var btnTestTbt: Button
    private lateinit var btnToggleFloatingPill: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        tvNavState = findViewById(R.id.tvNavState)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        btnModePiccolo = findViewById(R.id.btnModePiccolo)
        btnModeGrande = findViewById(R.id.btnModeGrande)
        btnModeSemplice = findViewById(R.id.btnModeSemplice)
        btnReleaseFocus = findViewById(R.id.btnReleaseFocus)
        btnTestTbt = findViewById(R.id.btnTestTbt)
        btnToggleFloatingPill = findViewById(R.id.btnToggleFloatingPill)

        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnGrantNotification.setOnClickListener {
            try {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Impostazioni notifiche non disponibili su DiLink. Permesso gestito via sistema/ADB.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnModePiccolo.setOnClickListener {
            NavClusterService.instance?.setMode(ClusterCommBridge.MODE_PICCOLO)
        }

        btnModeGrande.setOnClickListener {
            NavClusterService.instance?.setMode(ClusterCommBridge.MODE_GRANDE)
        }

        btnModeSemplice.setOnClickListener {
            NavClusterService.instance?.setMode(ClusterCommBridge.MODE_SEMPLICE)
        }

        btnReleaseFocus.setOnClickListener {
            NavClusterService.instance?.setMode(ClusterCommBridge.MODE_OFF)
        }

        btnTestTbt.setOnClickListener {
            val testState = NavState(
                isActive = true,
                sourcePackage = "com.google.android.apps.maps",
                sourceName = "Google Maps (Test)",
                distance = "250 m",
                instruction = "Svolta a destra in Via Roma",
                eta = "18:45",
                remainingTimeDistance = "12 min • 4.5 km"
            )
            NavClusterService.instance?.onNavStateChanged(testState)
        }

        btnToggleFloatingPill.setOnClickListener {
            NavClusterService.instance?.toggleFloatingPill()
        }

        // Auto-start background service
        NavClusterService.startService(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            tvOverlayStatus.text = "Overlay (Pulsante Flottante): ✓ Attivo"
            tvOverlayStatus.setTextColor(Color.parseColor("#00E676"))
            btnGrantOverlay.isEnabled = false
        } else {
            tvOverlayStatus.text = "Overlay (Pulsante Flottante): ✗ Mancante"
            tvOverlayStatus.setTextColor(Color.parseColor("#FF5252"))
            btnGrantOverlay.isEnabled = true
        }

        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        val hasNotification = enabledListeners.contains(packageName)
        if (hasNotification) {
            tvNotificationStatus.text = "Accesso Notifiche (Maps / ABRP): ✓ Attivo"
            tvNotificationStatus.setTextColor(Color.parseColor("#00E676"))
            btnGrantNotification.isEnabled = false
        } else {
            tvNotificationStatus.text = "Accesso Notifiche (Maps / ABRP): ✗ Mancante"
            tvNotificationStatus.setTextColor(Color.parseColor("#FF5252"))
            btnGrantNotification.isEnabled = true
        }

        val activeState = NavClusterService.instance?.currentNavState
        if (activeState != null && activeState.isActive) {
            tvNavState.text = "Navigazione attiva da ${activeState.sourceName}: ${activeState.instruction} (${activeState.distance})"
            tvNavState.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvNavState.text = "Navigazione attiva: In attesa di Maps/ABRP"
            tvNavState.setTextColor(Color.parseColor("#B0BEC5"))
        }
    }
}
