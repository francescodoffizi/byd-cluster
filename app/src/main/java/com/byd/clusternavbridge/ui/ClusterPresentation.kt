package com.byd.clusternavbridge.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.cluster.ClusterCommBridge
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.route.RouteFetcher
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.roundToInt

/**
 * ClusterPresentation v1.5.9
 *
 * Proiezione HDMI su Display 1 (1920x720) per BYD Sealion 7 / DiLink 4.0:
 * - MODE_GRANDE (4 - Intero): Mappa vettoriale 3D a pieno schermo con Card HUD svolta posizionata a
 *   topMargin = 220px (sotto la barra superiore opaca QNX Y=0..214px), cursore freccia 3D stabile
 *   senza lampeggio e tracciato itinerario OSRM in ciano brillante (#00E5FF).
 * - MODE_PICCOLO (3 - Piccolo): Mappa vettoriale 3D + Card HUD compatta posizionata dentro la
 *   finestra destra QNX (X=1340..1880, Y=220..640) a topMargin = 224px, leftMargin = 1380px,
 *   con padding camera (1340, 340, 40, 60) così il cursore sta nella metà inferiore del riquadro
 *   e il box delle svolte è sempre ben visibile sopra di esso.
 */
class ClusterPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    companion object {
        private const val TAG = "BydClusterPresentation"
        private const val STYLE_URL = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
        private const val ROUTE_SOURCE_ID = "byd-route-source"
        private const val ROUTE_CASING_LAYER_ID = "byd-route-casing-layer"
        private const val ROUTE_LINE_LAYER_ID = "byd-route-line-layer"
    }

    private var presentationTextureView: TextureView? = null
    private var mapContainer: FrameLayout? = null
    private var hudCard: LinearLayout? = null
    private var ivManeuverIcon: ImageView? = null
    private var tvManeuverDistance: TextView? = null
    private var tvStreetName: TextView? = null
    private var layoutTripRow: LinearLayout? = null
    private var tvCurrentSpeed: TextView? = null
    private var tvEta: TextView? = null
    private var tvRemainingDistance: TextView? = null
    private var tvClusterStatus: TextView? = null

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null

    private var currentMode: Int = ClusterCommBridge.MODE_GRANDE
    private var currentNavState: NavState = NavState()
    private var lastLocation: Location? = null
    private var lastRouteGeoJson: String? = null

    /**
     * Engine di localizzazione passivo: impedisce al LocationEngine interno di MapLibre di
     * competere con i fix GPS inviati da NavClusterService, eliminando il lampeggio (blink)
     * e la sparizione periodica del cursore veicolo.
     */
    private val passiveLocationEngine = object : LocationEngine {
        override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
            lastLocation?.let {
                callback.onSuccess(LocationEngineResult.create(it))
            }
        }

        override fun requestLocationUpdates(
            request: LocationEngineRequest,
            callback: LocationEngineCallback<LocationEngineResult>,
            looper: Looper?
        ) {
            // No-op: i fix GPS arrivano esclusivamente da NavClusterService.onLocationChanged
        }

        override fun requestLocationUpdates(
            request: LocationEngineRequest,
            pendingIntent: PendingIntent?
        ) {
            // No-op
        }

        override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {}
        override fun removeLocationUpdates(pendingIntent: PendingIntent?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setType(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }
            )
            addFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        setContentView(R.layout.activity_cluster_map)

        presentationTextureView = findViewById(R.id.presentationMapTextureView)
        mapContainer = findViewById(R.id.mapContainer)
        hudCard = findViewById(R.id.layoutCenterClusterHud)
        ivManeuverIcon = findViewById(R.id.ivManeuverIcon)
        tvManeuverDistance = findViewById(R.id.tvManeuverDistance)
        tvStreetName = findViewById(R.id.tvStreetName)
        layoutTripRow = findViewById(R.id.layoutTripRow)
        tvCurrentSpeed = findViewById(R.id.tvCurrentSpeed)
        tvEta = findViewById(R.id.tvEta)
        tvRemainingDistance = findViewById(R.id.tvRemainingDistance)
        tvClusterStatus = findViewById(R.id.tvClusterStatus)

        hudCard?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f
            setColor(Color.parseColor("#E6141E28"))
            setStroke(2, Color.parseColor("#4D00E5FF"))
        }

        // Nascondi la barra di stato inferiore di debug per massimizzare la pulizia visiva sul cluster
        tvClusterStatus?.visibility = View.GONE

        initMapLibre(savedInstanceState)
        applyModeLayout()
        renderNavState(currentNavState)
    }

    private fun initMapLibre(savedInstanceState: Bundle?) {
        try {
            MapLibre.getInstance(context)
            val options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .compassEnabled(false)
                .logoEnabled(false)
                .attributionEnabled(false)
                .rotateGesturesEnabled(false)
                .scrollGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .zoomGesturesEnabled(false)

            val mv = MapView(context, options)
            mapView = mv
            mapContainer?.removeAllViews()
            mapContainer?.addView(
                mv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            mapContainer?.visibility = View.VISIBLE

            mv.onCreate(savedInstanceState)
            mv.onStart()
            mv.onResume()

            mv.getMapAsync { map ->
                mapLibreMap = map
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                    Log.i(TAG, "MapLibre Dark-Matter style loaded on Display 1")
                    setupRouteLayers(style)
                    enableLocationComponent(map, style)
                    applyModeLayout()
                    lastLocation?.let { updateMapCamera(it) }
                    lastRouteGeoJson?.let { drawRouteGeoJson(it) }
                    triggerRouteFetchIfPossible()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MapLibre on cluster display: ${e.message}", e)
        }
    }

    private fun setupRouteLayers(style: Style) {
        try {
            if (style.getSource(ROUTE_SOURCE_ID) == null) {
                style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
            }

            if (style.getLayer(ROUTE_CASING_LAYER_ID) == null) {
                val casingLayer = LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineWidth(10.0f),
                    PropertyFactory.lineColor(Color.parseColor("#004D61")),
                    PropertyFactory.lineOpacity(0.90f)
                )
                style.addLayer(casingLayer)
            }

            if (style.getLayer(ROUTE_LINE_LAYER_ID) == null) {
                val lineLayer = LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineWidth(6.0f),
                    PropertyFactory.lineColor(Color.parseColor("#00E5FF")),
                    PropertyFactory.lineOpacity(0.98f)
                )
                style.addLayerAbove(lineLayer, ROUTE_CASING_LAYER_ID)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up route polyline layers: ${e.message}")
        }
    }

    private fun drawRouteGeoJson(geoJsonFeature: String) {
        lastRouteGeoJson = geoJsonFeature
        val style = mapLibreMap?.style ?: return
        if (!style.isFullyLoaded) return
        try {
            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            source?.setGeoJson(geoJsonFeature)
            Log.w(TAG, "Route polyline drawn on MapLibre Display 1 (${geoJsonFeature.length} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Error updating route GeoJSON source: ${e.message}")
        }
    }

    private fun clearRoutePolyline() {
        lastRouteGeoJson = null
        val style = mapLibreMap?.style ?: return
        if (!style.isFullyLoaded) return
        try {
            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            source?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing route GeoJSON source: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(map: MapLibreMap, style: Style) {
        try {
            // Disabilita pulseEnabled, accuracyAnimation e background/shadow per eliminare
            // definitivamente ogni pallino o alone circolare sotto la freccia 3D di navigazione.
            val locationOptions = LocationComponentOptions.builder(context)
                .pulseEnabled(false)
                .accuracyAnimationEnabled(false)
                .compassAnimationEnabled(true)
                .accuracyAlpha(0.0f)
                .gpsDrawable(R.drawable.ic_nav_cursor_arrow)
                .foregroundDrawable(R.drawable.ic_nav_cursor_arrow)
                .backgroundDrawable(android.R.color.transparent)
                .bearingTintColor(Color.TRANSPARENT)
                .backgroundTintColor(Color.TRANSPARENT)
                .foregroundTintColor(Color.TRANSPARENT)
                .enableStaleState(false)
                .elevation(0f)
                .build()

            val activationOptions = LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(locationOptions)
                .locationEngine(passiveLocationEngine)
                .useDefaultLocationEngine(false)
                .build()

            map.locationComponent.apply {
                activateLocationComponent(activationOptions)
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.GPS
            }
        } catch (e: Exception) {
            Log.w(TAG, "LocationComponent activation error: ${e.message}")
        }
    }

    fun updateMode(mode: Int) {
        currentMode = mode
        applyModeLayout()
    }

    /**
     * Posiziona la Card HUD e il centro camera della mappa in base alle maschere QNX reali
     * misurate sugli screenshot 1920x720 di BYD Sealion 7:
     *
     * - MODE_GRANDE (4 - Intero):
     *   La barra superiore QNX (tachimetro/orologio/batteria) copre in modo opaco Y = 0..214px.
     *   Posizioniamo quindi la Card HUD centrata a topMargin = 220px (larghezza 420px) così è
     *   100% visibile sotto il tachimetro, e il cursore mappa a Y = 500px (padding top = 340px).
     *
     * - MODE_PICCOLO (3 - Piccolo):
     *   La finestra trasparente destra ritagliata da QNX copre X = 1340..1880px, Y = 220..640px
     *   (sopra Y = 220px c'è lo sfondo montuoso QNX opaco!).
     *   Posizioniamo quindi la Card HUD nel riquadro destro a leftMargin = 1380px, topMargin = 224px,
     *   larghezza 400px, e spostiamo il cursore veicolo nella metà inferiore del riquadro destro
     *   con map.setPadding(1340, 340, 40, 60).
     */
    private fun applyModeLayout() {
        val card = hudCard ?: return
        val lp = (card.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(420, FrameLayout.LayoutParams.WRAP_CONTENT)

        when (currentMode) {
            ClusterCommBridge.MODE_PICCOLO -> {
                presentationTextureView?.visibility = View.VISIBLE
                mapContainer?.visibility = View.VISIBLE

                lp.width = 400
                lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = 1380
                lp.topMargin = 224
                lp.rightMargin = 0
                lp.bottomMargin = 0
                card.layoutParams = lp

                // Ancoraggio geometrico mapContainer esattamente dentro la finestra QNX destra (X=1350..1830, Y=220..680).
                // Con container dedicato 480x460, la camera 3D e il cursore sono centrati simmetricamente
                // senza deformazioni prospettiche o slittamenti laterali della carreggiata.
                val mapLp = (mapContainer?.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(480, 460)
                mapLp.gravity = Gravity.TOP or Gravity.START
                mapLp.width = 480
                mapLp.height = 460
                mapLp.leftMargin = 1350
                mapLp.topMargin = 220
                mapContainer?.layoutParams = mapLp

                mapLibreMap?.setPadding(0, 160, 0, 30)
            }

            ClusterCommBridge.MODE_SEMPLICE -> {
                // In modalità Semplice: card HUD attiva ad alto contrasto su sfondo nero OLED
                // e mappa spenta (zero carico GPU).
                presentationTextureView?.visibility = View.GONE
                mapContainer?.visibility = View.GONE

                lp.width = 400
                lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = 1380
                lp.topMargin = 224
                lp.rightMargin = 0
                lp.bottomMargin = 0
                card.layoutParams = lp
            }

            else -> {
                // MODE_GRANDE (4) / MODE_INTERO
                presentationTextureView?.visibility = View.VISIBLE
                mapContainer?.visibility = View.VISIBLE

                val mapLp = (mapContainer?.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                mapLp.gravity = Gravity.TOP or Gravity.START
                mapLp.width = FrameLayout.LayoutParams.MATCH_PARENT
                mapLp.height = FrameLayout.LayoutParams.MATCH_PARENT
                mapLp.leftMargin = 0
                mapLp.topMargin = 0
                mapContainer?.layoutParams = mapLp

                lp.width = 420
                lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.leftMargin = 0
                lp.topMargin = 220
                lp.rightMargin = 0
                lp.bottomMargin = 0
                card.layoutParams = lp

                // Centra il cursore veicolo nella metà inferiore dello schermo (X = 960, Y ≈ 520),
                // sotto la Card HUD posizionata a Y = 220..360.
                mapLibreMap?.setPadding(0, 360, 0, 60)
            }
        }

        lastLocation?.let { updateMapCamera(it) }
    }

    fun onLocationChanged(location: Location) {
        lastLocation = location
        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        tvCurrentSpeed?.text = speedKmh.toString()
        updateMapCamera(location)
        triggerRouteFetchIfPossible()
    }

    private fun updateMapCamera(location: Location) {
        val map = mapLibreMap ?: return
        try {
            if (map.locationComponent.isLocationComponentActivated) {
                map.locationComponent.forceLocationUpdate(location)
            }
        } catch (_: Exception) {}

        val targetZoom = if (currentMode == ClusterCommBridge.MODE_PICCOLO) 15.6 else 16.5
        val targetTilt = if (currentMode == ClusterCommBridge.MODE_PICCOLO) 42.0 else 52.0

        val camPos = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(targetZoom)
            .tilt(targetTilt)
            .bearing(if (location.hasBearing()) location.bearing.toDouble() else 0.0)
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(camPos), 650)
    }

    private fun triggerRouteFetchIfPossible() {
        val loc = lastLocation ?: return
        val state = currentNavState
        if (!state.isActive && state.distance.isBlank() && state.instruction.isBlank()) {
            return
        }

        RouteFetcher.fetchRouteIfNeeded(
            currentLat = loc.latitude,
            currentLon = loc.longitude,
            destination = state.destination,
            instruction = state.instruction
        ) { routeResult ->
            drawRouteGeoJson(routeResult.geoJsonFeature)
            updateTripRowWithFallback(currentNavState, routeResult)
        }
    }

    private fun updateTripRowWithFallback(
        state: NavState,
        routeResult: RouteFetcher.RouteResult? = RouteFetcher.getCachedRoute()
    ) {
        val effectiveEta = if (state.eta.isNotBlank() && state.eta != "--:--") {
            state.eta
        } else {
            routeResult?.formattedEta ?: "--:--"
        }

        val effectiveRem = if (state.remainingTimeDistance.isNotBlank()) {
            state.remainingTimeDistance
        } else {
            routeResult?.formattedSummary ?: ""
        }

        tvEta?.text = effectiveEta
        tvRemainingDistance?.text = effectiveRem
    }

    fun updateNavState(state: NavState) {
        currentNavState = state
        renderNavState(state)
    }

    private fun renderNavState(state: NavState) {
        if (!state.isActive && state.distance.isBlank() && state.instruction.isBlank()) {
            RouteFetcher.clearRoute()
            clearRoutePolyline()
            tvManeuverDistance?.text = "Pronto"
            tvStreetName?.text = "In attesa di indicazioni..."
            tvEta?.text = "--:--"
            tvRemainingDistance?.text = ""
            ivManeuverIcon?.setImageResource(R.drawable.ic_straight)
            return
        }

        tvManeuverDistance?.text = state.distance.ifBlank { "---" }
        val street = when {
            state.instruction.isNotBlank() -> state.instruction
            state.destination.isNotBlank() -> state.destination
            else -> "Segui il percorso"
        }
        tvStreetName?.text = street

        updateTripRowWithFallback(state)
        ivManeuverIcon?.setImageResource(resolveTurnDrawable(street))
        triggerRouteFetchIfPossible()
    }

    private fun resolveTurnDrawable(instr: String): Int {
        val lower = instr.lowercase()
        return when {
            lower.contains("inversione") || lower.contains("u-turn") -> R.drawable.ic_uturn
            lower.contains("rotonda") || lower.contains("rotatoria") || lower.contains("roundabout") -> R.drawable.ic_roundabout
            lower.contains("destra") || lower.contains("right") -> {
                if (lower.contains("legger") || lower.contains("slight") || lower.contains("tieni") || lower.contains("keep")) {
                    R.drawable.ic_turn_slight_right
                } else {
                    R.drawable.ic_turn_right
                }
            }
            lower.contains("sinistra") || lower.contains("left") -> {
                if (lower.contains("legger") || lower.contains("slight") || lower.contains("tieni") || lower.contains("keep")) {
                    R.drawable.ic_turn_slight_left
                } else {
                    R.drawable.ic_turn_left
                }
            }
            else -> R.drawable.ic_straight
        }
    }

    fun cleanup() {
        try {
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        } catch (_: Exception) {}
        mapView = null
        mapLibreMap = null
    }
}
