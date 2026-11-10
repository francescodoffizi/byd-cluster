package com.byd.clusternavbridge.route

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Asynchronous route geocoder and OSRM polyline fetcher for MapLibre cluster map.
 *
 * Resolves destination or target street names near the vehicle's current position
 * using Photon (Komoot) with Nominatim fallback, then queries OSRM for the full
 * driving route geometry (GeoJSON LineString) + total distance and duration.
 */
object RouteFetcher {
    private const val TAG = "RouteFetcher"

    data class RouteResult(
        val query: String,
        val destLat: Double,
        val destLon: Double,
        val geoJsonFeature: String,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val formattedSummary: String,
        val formattedEta: String,
        val coordinates: List<Pair<Double, Double>>
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastRoute: RouteResult? = null

    @Volatile
    private var lastAttemptedQuery: String = ""

    @Volatile
    private var lastAttemptTimestamp: Long = 0L

    @Volatile
    private var isFetching: Boolean = false

    fun getCachedRoute(): RouteResult? = lastRoute

    fun clearRoute() {
        lastRoute = null
        lastAttemptedQuery = ""
    }

    /**
     * Extracts a clean geocodable query from NavState destination or instruction.
     */
    fun extractTargetQuery(destination: String, instruction: String): String {
        val cleanDest = destination.trim()
        if (cleanDest.length >= 3 && !cleanDest.equals("Navigazione attiva", ignoreCase = true)) {
            return cleanDest
        }

        var cleanInst = instruction.trim()
        if (cleanInst.isEmpty()) return ""

        // Strip common Italian/English turn prefixes to isolate the target road/place name
        val prefixPatterns = listOf(
            Regex("^(?:Tra|Fra|In)\\s+\\d+(?:[.,]\\d+)?\\s*(?:m|km|mi|ft|yd|metri|chilometri)\\s*,?\\s*", RegexOption.IGNORE_CASE),
            Regex("^(?:Alla rotonda,?\\s*)?(?:prendi la \\d+[ªa°]? uscita\\s*(?:su|in|per|verso)?\\s*)", RegexOption.IGNORE_CASE),
            Regex("^(?:Svolta\\s+(?:stretta\\s+|leggermente\\s+)?a\\s+(?:destra|sinistra)\\s+(?:su|in|per|verso)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Gira\\s+a\\s+(?:destra|sinistra)\\s+(?:su|in|per|verso)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Tieni\\s+la\\s+(?:destra|sinistra)\\s+(?:su|in|per|verso)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Prosegui\\s+(?:dritto\\s+)?(?:su|in|per|verso)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Continua\\s+(?:su|in|per|verso)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Imbocca\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Prendi\\s+(?:l'uscita\\s+)?(?:su|in|per|verso)?\\s*)", RegexOption.IGNORE_CASE),
            Regex("^(?:Turn\\s+(?:sharp\\s+|slight\\s+)?(?:left|right)\\s+onto\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Keep\\s+(?:left|right)\\s+(?:onto|towards)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:Continue\\s+(?:on|onto)\\s+)", RegexOption.IGNORE_CASE),
            Regex("^(?:verso|su|in)\\s+", RegexOption.IGNORE_CASE)
        )

        for (regex in prefixPatterns) {
            cleanInst = regex.replace(cleanInst, "").trim()
        }

        // Ignore generic maneuver strings that have no street name
        val genericManeuvers = setOf(
            "svolta a destra", "svolta a sinistra", "svolta stretta a destra", "svolta stretta a sinistra",
            "tieni la destra", "tieni la sinistra", "inversione a u", "inversione a u a destra",
            "alla rotonda", "arrivo a destinazione", "prosegui dritto", "navigazione attiva",
            "ricalcolo percorso", "ricalcolo..."
        )
        if (cleanInst.lowercase(Locale.ITALIAN) in genericManeuvers || cleanInst.length < 3) {
            return ""
        }

        return cleanInst
    }

    /**
     * Checks if a new route fetch is needed based on query changes or deviation from the active route,
     * and dispatches an async fetch if needed.
     */
    fun fetchRouteIfNeeded(
        currentLat: Double,
        currentLon: Double,
        destination: String,
        instruction: String,
        onResult: (RouteResult) -> Unit
    ) {
        if (currentLat == 0.0 && currentLon == 0.0) return

        val query = extractTargetQuery(destination, instruction)
        val cached = lastRoute

        if (query.isEmpty()) {
            // If we already have a cached route and we are still on it, keep it
            if (cached != null) {
                onResult(cached)
            }
            return
        }

        val now = System.currentTimeMillis()
        val sameQuery = cached != null && cached.query.equals(query, ignoreCase = true)

        if (sameQuery && cached != null) {
            val minDistToRoute = minDistanceToPolylineMeters(currentLat, currentLon, cached.coordinates)
            if (minDistToRoute < 180.0) {
                // Still on the cached route
                onResult(cached)
                return
            }
            Log.i(TAG, "Vehicle deviated ${minDistToRoute.toInt()}m from cached route for '$query', recalculating...")
        }

        if (isFetching) return
        if (query.equals(lastAttemptedQuery, ignoreCase = true) && (now - lastAttemptTimestamp) < 12_000L) {
            return
        }

        isFetching = true
        lastAttemptedQuery = query
        lastAttemptTimestamp = now

        executor.execute {
            try {
                Log.w(TAG, "Resolving route from ($currentLat, $currentLon) to query='$query'")
                val destCoords = geocodeDestination(query, currentLat, currentLon)
                if (destCoords == null) {
                    Log.w(TAG, "Could not geocode query='$query'")
                    return@execute
                }

                val (destLat, destLon) = destCoords
                val distToDest = haversineMeters(currentLat, currentLon, destLat, destLon)
                if (distToDest < 15.0 || distToDest > 2500_000.0) {
                    Log.w(TAG, "Geocoded target ($destLat, $destLon) distance=${distToDest.toInt()}m out of range")
                    return@execute
                }

                val routeResult = fetchOsrmRoute(query, currentLat, currentLon, destLat, destLon)
                if (routeResult != null) {
                    lastRoute = routeResult
                    Log.w(
                        TAG,
                        "OSRM route ready for '$query': points=${routeResult.coordinates.size}, " +
                            "summary=${routeResult.formattedSummary}, eta=${routeResult.formattedEta}"
                    )
                    mainHandler.post {
                        onResult(routeResult)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Route fetch failed for '$query': ${e.message}")
            } finally {
                isFetching = false
            }
        }
    }

    private fun geocodeDestination(query: String, biasLat: Double, biasLon: Double): Pair<Double, Double>? {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // 1. Photon (Komoot) with location bias (fast, great for local streets & POIs)
        try {
            val photonUrl = "https://photon.komoot.io/api/?q=$encoded&lat=$biasLat&lon=$biasLon&limit=3"
            val body = httpGet(photonUrl, 5000)
            if (!body.isNullOrEmpty()) {
                val json = JSONObject(body)
                val features = json.optJSONArray("features")
                if (features != null && features.length() > 0) {
                    // Pick the closest reasonable candidate (> 25m away)
                    var bestPair: Pair<Double, Double>? = null
                    var bestDist = Double.MAX_VALUE
                    for (i in 0 until features.length()) {
                        val geom = features.getJSONObject(i).optJSONObject("geometry") ?: continue
                        val coords = geom.optJSONArray("coordinates") ?: continue
                        if (coords.length() >= 2) {
                            val lon = coords.getDouble(0)
                            val lat = coords.getDouble(1)
                            val d = haversineMeters(biasLat, biasLon, lat, lon)
                            if (d > 25.0 && d < bestDist) {
                                bestDist = d
                                bestPair = Pair(lat, lon)
                            }
                        }
                    }
                    if (bestPair != null) return bestPair
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Photon geocode error: ${e.message}")
        }

        // 2. Nominatim fallback with bounded viewbox around vehicle (~35km box)
        try {
            val delta = 0.35
            val viewbox = "${biasLon - delta},${biasLat + delta},${biasLon + delta},${biasLat - delta}"
            val nomUrl = "https://nominatim.openstreetmap.org/search?format=json&q=$encoded&viewbox=$viewbox&bounded=0&limit=1"
            val body = httpGet(nomUrl, 5000)
            if (!body.isNullOrEmpty() && body.trim().startsWith("[")) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val first = arr.getJSONObject(0)
                    val lat = first.getString("lat").toDouble()
                    val lon = first.getString("lon").toDouble()
                    return Pair(lat, lon)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim geocode error: ${e.message}")
        }

        return null
    }

    private fun fetchOsrmRoute(
        query: String,
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double
    ): RouteResult? {
        val osrmUrl = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            startLon,
            startLat,
            destLon,
            destLat
        )

        val body = httpGet(osrmUrl, 6000) ?: return null
        val root = JSONObject(body)
        if (root.optString("code") != "Ok") return null

        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null

        val route = routes.getJSONObject(0)
        val geometry = route.optJSONObject("geometry") ?: return null
        val coordsArray = geometry.optJSONArray("coordinates") ?: return null
        if (coordsArray.length() < 2) return null

        val coordList = ArrayList<Pair<Double, Double>>(coordsArray.length())
        for (i in 0 until coordsArray.length()) {
            val pt = coordsArray.getJSONArray(i)
            val lon = pt.getDouble(0)
            val lat = pt.getDouble(1)
            coordList.add(Pair(lat, lon))
        }

        val distanceMeters = route.optDouble("distance", 0.0)
        val durationSeconds = route.optDouble("duration", 0.0)

        val formattedDist = if (distanceMeters >= 1000.0) {
            String.format(Locale.ITALIAN, "%.1f km", distanceMeters / 1000.0)
        } else {
            "${distanceMeters.toInt()} m"
        }

        val totalMinutes = (durationSeconds / 60.0).toInt().coerceAtLeast(1)
        val formattedTime = if (totalMinutes >= 60) {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            "${h}h ${m}m"
        } else {
            "$totalMinutes min"
        }

        val formattedSummary = "$formattedTime · $formattedDist"
        val etaMillis = System.currentTimeMillis() + (durationSeconds * 1000.0).toLong()
        val formattedEta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(etaMillis))

        val featureJson = JSONObject().apply {
            put("type", "Feature")
            put("properties", JSONObject())
            put("geometry", geometry)
        }.toString()

        return RouteResult(
            query = query,
            destLat = destLat,
            destLon = destLon,
            geoJsonFeature = featureJson,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            formattedSummary = formattedSummary,
            formattedEta = formattedEta,
            coordinates = coordList
        )
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "BydClusterNavBridge/1.5.9 (Android Automotive)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                null
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun minDistanceToPolylineMeters(
        lat: Double,
        lon: Double,
        coords: List<Pair<Double, Double>>
    ): Double {
        var minDist = Double.MAX_VALUE
        for (pt in coords) {
            val d = haversineMeters(lat, lon, pt.first, pt.second)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
