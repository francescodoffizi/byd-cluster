package com.byd.clusternavbridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.databinding.ActivityClusterMapBinding
import com.byd.clusternavbridge.power.VehiclePowerMonitor
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Dedicated Cockpit Vector Map Activity running natively on Display 1 (Schermo HDMI 1920x720).
 *
 * Runs completely decoupled from Display 0: The cluster map continues rendering smoothly at 60 fps
 * even when the central tablet switches to Spotify, AVM cameras, vehicle settings, or goes home.
 */
class ClusterMapActivity : AppCompatActivity(), LocationListener {

    companion object {
        private const val TAG = "ClusterMapActivity"

        const val ACTION_UPDATE_NAV_DATA = "com.byd.clusternavbridge.action.UPDATE_NAV_DATA"
        const val ACTION_CLOSE_CLUSTER_MAP = "com.byd.clusternavbridge.action.CLOSE_CLUSTER_MAP"

        const val EXTRA_STREET = "extra_street"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_ICON_ID = "extra_icon_id"
        const val EXTRA_ETA = "extra_eta"
        const val EXTRA_REMAINING_DIST = "extra_remaining_dist"

        // Dark Matter GL style - ultra-high contrast dark cockpit theme
        private const val DARK_STYLE_URL = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
        // Fallback demo style
        private const val FALLBACK_STYLE_URL = "https://demotiles.maplibre.org/style.json"
    }

    private lateinit var binding: ActivityClusterMapBinding
    private var mapLibreMap: MapLibreMap? = null
    private var locationManager: LocationManager? = null

    private var lastLocation: Location? = null
    private var isFirstFix = true

    private val navDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_UPDATE_NAV_DATA -> {
                    val street = intent.getStringExtra(EXTRA_STREET) ?: ""
                    val distance = intent.getStringExtra(EXTRA_DISTANCE) ?: ""
                    val iconId = intent.getIntExtra(EXTRA_ICON_ID, 1)
                    val eta = intent.getStringExtra(EXTRA_ETA) ?: ""
                    val remainingDist = intent.getStringExtra(EXTRA_REMAINING_DIST) ?: ""

                    updateHud(street, distance, iconId, eta, remainingDist)
                }
                ACTION_CLOSE_CLUSTER_MAP, VehiclePowerMonitor.ACTION_ACC_OFF -> {
                    Log.i(TAG, "Close signal or ACC OFF received, finishing ClusterMapActivity")
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ClusterMapActivity onCreate on display: ${window.decorView.display?.displayId}")

        // Initialize MapLibre before layout inflation
        MapLibre.getInstance(this)

        // Make truly fullscreen without decor bars
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        binding = ActivityClusterMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map
            setupMap(map)
        }

        // Register nav & power updates
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_NAV_DATA)
            addAction(ACTION_CLOSE_CLUSTER_MAP)
            addAction(VehiclePowerMonitor.ACTION_ACC_OFF)
        }
        registerReceiver(navDataReceiver, filter)

        // Setup GPS location updates
        setupLocationUpdates()
    }

    private fun setupMap(map: MapLibreMap) {
        // Set camera tilt for 3D perspective cockpit driving view
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isZoomGesturesEnabled = false
        map.uiSettings.isScrollGesturesEnabled = false

        map.setStyle(DARK_STYLE_URL) { style ->
            Log.i(TAG, "MapLibre Dark Matter style loaded successfully")
            binding.tvClusterStatus.text = "BYD Cluster Nav • 3D Map Active"
            lastLocation?.let { updateCamera(it, animate = false) }
        }
    }

    private fun setupLocationUpdates() {
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val lm = locationManager ?: return

            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (gpsEnabled) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, this)
            } else if (networkEnabled) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1.0f, this)
            }

            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing or restricted", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error initializing LocationManager", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location

        // Update speed indicator (m/s to km/h)
        val speedKmh = (location.speed * 3.6f).toInt().coerceAtLeast(0)
        binding.tvCurrentSpeed.text = speedKmh.toString()

        updateCamera(location, animate = !isFirstFix)
        isFirstFix = false
    }

    private fun updateCamera(location: Location, animate: Boolean) {
        val map = mapLibreMap ?: return
        val target = LatLng(location.latitude, location.longitude)
        val bearing = if (location.hasBearing() && location.bearing != 0.0f) {
            location.bearing.toDouble()
        } else {
            map.cameraPosition.bearing
        }

        val cameraPosition = CameraPosition.Builder()
            .target(target)
            .zoom(16.5)
            .tilt(55.0) // 3D driving perspective
            .bearing(bearing)
            .build()

        if (animate) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 900)
        } else {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        }
    }

    private fun updateHud(
        street: String,
        distance: String,
        iconId: Int,
        eta: String,
        remainingDist: String
    ) {
        if (street.isNotEmpty()) {
            binding.tvStreetName.text = street
        }
        if (distance.isNotEmpty()) {
            binding.tvManeuverDistance.text = distance
        }
        if (eta.isNotEmpty()) {
            binding.tvEta.text = eta
        }
        if (remainingDist.isNotEmpty()) {
            binding.tvRemainingDistance.text = remainingDist
        }

        // Map vector maneuver icons
        val iconRes = when (iconId) {
            2, 4 -> R.drawable.ic_turn_left
            3, 5 -> R.drawable.ic_turn_right
            6, 7 -> R.drawable.ic_uturn
            11, 12, 13 -> R.drawable.ic_roundabout
            else -> R.drawable.ic_straight
        }
        binding.ivManeuverIcon.setImageResource(iconRes)
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ClusterMapActivity onDestroy - releasing EGL and Location resources")
        try {
            unregisterReceiver(navDataReceiver)
        } catch (_: Exception) {}

        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}

        binding.mapView.onDestroy()
    }
}
