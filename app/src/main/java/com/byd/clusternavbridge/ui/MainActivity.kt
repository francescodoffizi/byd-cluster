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
    private lateinit var tvSecureSettingsStatus: TextView
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

        findViewById<TextView>(R.id.tvAppSubtitle)?.text = "v${com.byd.clusternavbridge.BuildConfig.VERSION_NAME} - TBT & Display Neusoft OEM"

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        tvSecureSettingsStatus = findViewById(R.id.tvSecureSettingsStatus)
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
            val service = NavClusterService.instance
            if (service == null) {
                NavClusterService.startService(this)
                Toast.makeText(this, "Avvio del servizio in corso...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val testState = NavState(
                isActive = true,
                sourcePackage = "com.google.android.apps.maps",
                sourceName = "Google Maps (Test)",
                distance = "250 m",
                instruction = "Svolta a destra in Via Roma",
                eta = "18:45",
                remainingTimeDistance = "12 min • 4.5 km"
            )
            service.onNavStateChanged(testState)
            tvNavState.text = "Simulazione attiva: ${testState.instruction} (${testState.distance})"
            tvNavState.setTextColor(Color.parseColor("#00E676"))
            Toast.makeText(this, "Simulazione TBT inviata: Svolta a destra in 250m", Toast.LENGTH_SHORT).show()
        }

        btnToggleFloatingPill.setOnClickListener {
            val newState = NavClusterService.instance?.toggleFloatingPill() ?: false
            btnToggleFloatingPill.text = if (newState) "Nascondi Baloon Flottante" else "Mostra Baloon Flottante"
        }

        // Auto-start background service
        NavClusterService.startService(this)

        NavClusterService.instance?.navStateListener = { state ->
            runOnUiThread {
                if (state.isActive) {
                    tvNavState.text = "Navigazione attiva da ${state.sourceName}: ${state.instruction} (${state.distance})"
                    tvNavState.setTextColor(Color.parseColor("#00E676"))
                } else {
                    tvNavState.text = "Navigazione attiva: In attesa di Maps/ABRP"
                    tvNavState.setTextColor(Color.parseColor("#B0BEC5"))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        NavClusterService.instance?.navStateListener = { state ->
            runOnUiThread {
                if (state.isActive) {
                    tvNavState.text = "Navigazione attiva da ${state.sourceName}: ${state.instruction} (${state.distance})"
                    tvNavState.setTextColor(Color.parseColor("#00E676"))
                } else {
                    tvNavState.text = "Navigazione attiva: In attesa di Maps/ABRP"
                    tvNavState.setTextColor(Color.parseColor("#B0BEC5"))
                }
            }
        }
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

        val hasSecureSettings = checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasSecureSettings) {
            tvSecureSettingsStatus.text = "Controllo Quadro (WRITE_SECURE_SETTINGS): ✓ Concesso"
            tvSecureSettingsStatus.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvSecureSettingsStatus.text = "Controllo Quadro (WRITE_SECURE_SETTINGS): ✗ Mancante (richiede adb grant)"
            tvSecureSettingsStatus.setTextColor(Color.parseColor("#FF5252"))
        }

        val prefs = getSharedPreferences("byd_cluster_prefs", android.content.Context.MODE_PRIVATE)
        val pillEnabled = prefs.getBoolean(NavClusterService.KEY_FLOATING_PILL_ENABLED, false)
        btnToggleFloatingPill.text = if (pillEnabled) "Nascondi Baloon Flottante" else "Mostra Baloon Flottante"

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
