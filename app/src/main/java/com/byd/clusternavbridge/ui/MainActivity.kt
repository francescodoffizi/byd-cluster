package com.byd.clusternavbridge.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.service.NavClusterService

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var tvNavState: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantNotification: Button
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
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        }

        btnModeSemplice.setOnClickListener {
            val serviceIntent = Intent(this, NavClusterService::class.java).apply {
                action = NavClusterService.ACTION_ENABLE_TBT
            }
            startService(serviceIntent)
        }

        btnReleaseFocus.setOnClickListener {
            val serviceIntent = Intent(this, NavClusterService::class.java).apply {
                action = NavClusterService.ACTION_DISABLE_TBT
            }
            startService(serviceIntent)
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
            btnGrantOverlay.text = "OK"
        } else {
            tvOverlayStatus.text = "Overlay (Pulsante Flottante): ✗ Richiesto"
            tvOverlayStatus.setTextColor(Color.parseColor("#FF5252"))
            btnGrantOverlay.isEnabled = true
            btnGrantOverlay.text = "Abilita"
        }

        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        val hasNotificationAccess = enabledListeners.contains(packageName)
        if (hasNotificationAccess) {
            tvNotificationStatus.text = "Accesso Notifiche: ✓ Connesso"
            tvNotificationStatus.setTextColor(Color.parseColor("#00E676"))
            btnGrantNotification.isEnabled = false
            btnGrantNotification.text = "OK"
        } else {
            tvNotificationStatus.text = "Accesso Notifiche: ✗ Disconnesso (Frecce spente)"
            tvNotificationStatus.setTextColor(Color.parseColor("#FF5252"))
            btnGrantNotification.isEnabled = true
            btnGrantNotification.text = "Abilita"
        }

        val navState = NavClusterService.instance?.currentNavState
        if (navState != null && navState.isActive) {
            tvNavState.text = "Navigazione attiva: ${navState.sourceName} -> ${navState.distance} ${navState.instruction}"
            tvNavState.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvNavState.text = "Navigazione attiva: Nessuna (In attesa di Maps/ABRP)"
            tvNavState.setTextColor(Color.parseColor("#B0BEC5"))
        }
    }
}
