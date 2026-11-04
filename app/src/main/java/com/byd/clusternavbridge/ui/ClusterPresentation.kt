package com.byd.clusternavbridge.ui

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.databinding.ActivityClusterMapBinding
import com.byd.clusternavbridge.model.NavState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * ClusterPresentation v1.4.0
 *
 * Presentation autonoma renderizzata su Display 1 hardware (Schermo HDMI 1920x720)
 * secondo l'architettura OEM Neusoft/BYD.
 *
 * Gira in-process: Google Maps sul Display 0 (tablet centrale) rimane completamente
 * intatto senza viewport detach né crash audio/microfono.
 */
class ClusterPresentation(
    context: Context,
    display: Display
) : Presentation(context, display), LocationListener {

    companion object {
        private const val TAG = "ClusterPresentation"
        private const val DARK_STYLE_URL = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
    }

    private lateinit var binding: ActivityClusterMapBinding
    private var mapLibreMap: MapLibreMap? = null
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private var isFirstFix = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ClusterPresentation onCreate on display: ${display.displayId} (${display.name})")

        // Inizializza MapLibre prima dell'inflater
        MapLibre.getInstance(context)

        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        binding = ActivityClusterMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClusterSurfaceInjection()

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map
            setupMap(map)
        }

        setupLocationUpdates()
    }

    private fun setupMap(map: MapLibreMap) {
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isZoomGesturesEnabled = false
        map.uiSettings.isScrollGesturesEnabled = false

        map.setStyle(DARK_STYLE_URL) {
            Log.i(TAG, "MapLibre Dark Matter style loaded successfully on Cluster Display")
            binding.tvClusterStatus.text = "BYD Cluster Nav • 3D Map Active"
            lastLocation?.let { updateCamera(it, animate = false) }
        }
    }

    private fun setupLocationUpdates() {
        try {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                Log.w(TAG, "Location permissions not granted for ClusterPresentation")
                return
            }

            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val lm = locationManager ?: return

            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (gpsEnabled && hasFine) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, this)
            } else if (networkEnabled) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1.0f, this)
            }

            val lastKnown = if (hasFine) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            lastKnown?.let { onLocationChanged(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error initializing LocationManager on ClusterPresentation", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
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
            .tilt(50.0) // 3D cockpit perspective
            .bearing(bearing)
            .build()

        if (animate) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 900)
        } else {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        }
    }

    fun updateNavState(navState: NavState) {
        if (!::binding.isInitialized) return
        if (navState.instruction.isNotEmpty()) {
            binding.tvStreetName.text = navState.instruction
        }
        if (navState.distance.isNotEmpty()) {
            binding.tvManeuverDistance.text = navState.distance
        }
        if (navState.eta.isNotEmpty()) {
            binding.tvEta.text = navState.eta
        }
        if (navState.remainingTimeDistance.isNotEmpty()) {
            binding.tvRemainingDistance.text = navState.remainingTimeDistance
        }

        val iconId = mapInstructionToIcon(navState.instruction)
        val iconRes = when (iconId) {
            2, 4 -> R.drawable.ic_turn_left
            3, 5 -> R.drawable.ic_turn_right
            6, 7 -> R.drawable.ic_uturn
            11, 12, 13 -> R.drawable.ic_roundabout
            else -> R.drawable.ic_straight
        }
        binding.ivManeuverIcon.setImageResource(iconRes)
    }

    private fun mapInstructionToIcon(instr: String): Int {
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
            else -> 1
        }
    }

    private fun setupClusterSurfaceInjection() {
        val textureView = binding.presentationMapTextureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "onSurfaceTextureAvailable ($width x $height) -> injecting clusterSurface to QNX compositor")
                sendClusterSurface(Surface(surfaceTexture))
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "onSurfaceTextureSizeChanged ($width x $height)")
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                Log.i(TAG, "onSurfaceTextureDestroyed -> resetting clusterSurface")
                sendClusterSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // Keep frame flow continuous
            }
        }
    }

    private fun sendClusterSurface(surface: Surface?) {
        try {
            val intent = Intent("com.neusoft.na.navigation.set.cluster.surface").apply {
                putExtra("clusterSurface", surface)
            }
            context.sendStickyBroadcast(intent)
            Log.i(TAG, "Sticky broadcast sent for clusterSurface: $surface")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send clusterSurface sticky broadcast", e)
        }
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "ClusterPresentation onStop")
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        binding.mapView.onStop()
    }

    fun cleanup() {
        Log.i(TAG, "ClusterPresentation cleanup")
        sendClusterSurface(null)
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        if (::binding.isInitialized) {
            binding.mapView.onDestroy()
        }
    }
}
