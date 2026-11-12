package com.byd.clusternavbridge.route

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route provider for the MapLibre cluster display:
 *
 * 1. **Authoritative 1:1 Route Store**: Stores exact GeoJSON polylines pushed directly by
 *    bridged navigation apps (e.g. ABRP via `AbrpRouteBridge` -> `route_geojson_gzip`),
 *    preserving EV charging stops, waypoints, and live traffic reroutes without calling OSRM.
 * 2. **Hardened OSRM Fallback** (only when no authoritative route is active AND `destination`
 *    is a genuine geocodable address, never a symbolic favorite or next-turn street name):
 *    strips summary prefixes ("Viaggio verso ..."), blocks symbolic names ("Casa", "Lavoro"),
 *    and validates Photon/Nominatim candidates + OSRM distance against `remainingTimeDistance`.
 */
object RouteFetcher {
    private const val TAG = "RouteFetcher"
    private const val MAX_DECOMPRESSED_GEOJSON_BYTES = 2 * 1024 * 1024

    data class AuthoritativeRoute(
        val sourcePackage: String,
        val geoJson: String,
        val routeLengthMeters: Double,
        val updatedAtMs: Long
    )

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
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val authoritativeRoutes = ConcurrentHashMap<String, AuthoritativeRoute>()

    @Volatile
    private var lastRoute: RouteResult? = null

    @Volatile
    private var lastAttemptedQuery: String = ""

    @Volatile
    private var lastAttemptTimestamp: Long = 0L

    @Volatile
    private var isFetching: Boolean = false

    private val failedQueries = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val GENERIC_NAV_ACTIVE_REGEX = Regex("^navigazione\\b.*\\battiva$", RegexOption.IGNORE_CASE)

    private val DESTINATION_SUMMARY_PREFIXES = listOf(
        Regex("^(?:in\\s+viaggio\\s+verso|viaggio\\s+verso|viaggio\\s+per|rotta\\s+verso|navigazione\\s+verso|direzione|arrivo\\s+a|verso)\\s+:?\\s*", RegexOption.IGNORE_CASE),
        Regex("^(?:destinazione)\\s*:\\s*", RegexOption.IGNORE_CASE),
        Regex("^(?:trip\\s+to|route\\s+to|heading\\s+to|navigating\\s+to|arriving\\s+at|towards)\\s+:?\\s*", RegexOption.IGNORE_CASE)
    )

    private val NON_GEOCODABLE_PLACEHOLDERS = setOf(
        "navigazione attiva",
        "navigazione vocale",
        "navigazione in corso",
        "in navigazione",
        "navigazione",
        "navbridge pronto",
        "pronto",
        "in attesa di indicazioni...",
        "in attesa di indicazioni",
        "prosegui sul percorso",
        "segui il percorso",
        "prosegui dritto",
        "in rotta",
        "ricalcolo...",
        "ricalcolo percorso",
        "ricalcolo",
        "here wego",
        "abrp",
        "a better routeplanner",
        "google maps",
        "maps",
        "osmand",
        "waze",
        // Symbolic saved-place / favorite labels that must NEVER be geocoded as random POIs
        "casa",
        "lavoro",
        "ufficio",
        "palestra",
        "scuola",
        "università",
        "universita",
        "ospedale",
        "stazione",
        "aeroporto",
        "parcheggio",
        "supermercato",
        "home",
        "work",
        "office",
        "gym",
        "school",
        "favorite",
        "favourite",
        "preferito",
        "posizione salvata",
        "posizione attuale",
        "destinazione",
        "arrivo a destinazione",
        "punto sulla mappa",
        "pinned location",
        "saved location",
        "colonnina",
        "stazione di ricarica",
        "charging station"
    )

    fun setAuthoritativeRoute(
        sourcePackage: String,
        geoJson: String,
        routeLengthMeters: Double = 0.0,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (sourcePackage.isBlank() || geoJson.isBlank()) return
        authoritativeRoutes[sourcePackage] = AuthoritativeRoute(
            sourcePackage = sourcePackage,
            geoJson = geoJson,
            routeLengthMeters = routeLengthMeters,
            updatedAtMs = nowMs
        )
        // Clear any guessed OSRM fallback route so stale polylines never coexist
        lastRoute = null
    }

    fun getAuthoritativeRoute(sourcePackage: String): AuthoritativeRoute? {
        if (sourcePackage.isBlank()) return null
        return authoritativeRoutes[sourcePackage]
    }

    fun clearAuthoritativeRoute(sourcePackage: String) {
        if (sourcePackage.isBlank()) return
        authoritativeRoutes.remove(sourcePackage)
    }

    fun clearAllAuthoritativeRoutes() {
        authoritativeRoutes.clear()
    }

    fun decompressGzipGeoJson(compressed: ByteArray?): String? {
        if (compressed == null || compressed.isEmpty()) return null
        return try {
            ByteArrayInputStream(compressed).use { bais ->
                GZIPInputStream(bais).use { gis ->
                    val out = ByteArrayOutputStream(minOf(compressed.size * 4, 65536))
                    val buf = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val read = gis.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DECOMPRESSED_GEOJSON_BYTES) {
                            Log.w(TAG, "Rejected oversized GZIP GeoJSON (> $MAX_DECOMPRESSED_GEOJSON_BYTES bytes)")
                            return null
                        }
                        out.write(buf, 0, read)
                    }
                    val str = out.toString(Charsets.UTF_8.name()).trim()
                    if (str.startsWith("{") && str.contains("\"type\"")) str else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decompress route_geojson_gzip: ${e.message}")
            null
        }
    }

    fun getCachedRoute(): RouteResult? = lastRoute

    fun clearRoute() {
        lastRoute = null
        lastAttemptedQuery = ""
        failedQueries.clear()
    }

    private fun normalizeSpaces(raw: String): String {
        return raw
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Strips leading route-summary prefixes such as "Viaggio verso ", "Trip to ", "Verso ".
     */
    fun cleanDestinationLabel(raw: String): String {
        var cleaned = normalizeSpaces(raw)
        if (cleaned.isEmpty()) return ""
        var changed = true
        while (changed && cleaned.isNotEmpty()) {
            changed = false
            for (prefix in DESTINATION_SUMMARY_PREFIXES) {
                val next = prefix.replaceFirst(cleaned, "").trim()
                if (next != cleaned) {
                    cleaned = next
                    changed = true
                }
            }
        }
        return cleaned
    }

    fun isNonGeocodablePlaceholder(raw: String): Boolean {
        val norm = normalizeSpaces(raw)
        if (norm.length < 3) return true
        val lower = norm.lowercase(Locale.ITALIAN)
        if (lower in NON_GEOCODABLE_PLACEHOLDERS) return true
        if (GENERIC_NAV_ACTIVE_REGEX.matches(lower)) return true

        val stripped = cleanDestinationLabel(norm)
        if (stripped.length < 3) return true
        val strippedLower = stripped.lowercase(Locale.ITALIAN)
        if (strippedLower in NON_GEOCODABLE_PLACEHOLDERS) return true
        if (GENERIC_NAV_ACTIVE_REGEX.matches(strippedLower)) return true
        return false
    }

    /**
     * Extracts a geocodable destination query ONLY from `destination` (never from `instruction`).
     *
     * Rationale: geocoding next-turn street/highway names (e.g. HERE WeGo "E80 A90" or
     * "Esci a Uscita 32 Pisana") picks an arbitrary point on that road and makes OSRM invent
     * wrong exits and fake U-turns on dual carriageways.
     */
    fun extractTargetQuery(destination: String, @Suppress("UNUSED_PARAMETER") instruction: String = ""): String {
        val strippedDest = cleanDestinationLabel(destination)
        if (strippedDest.length >= 3 && !isNonGeocodablePlaceholder(strippedDest)) {
            return strippedDest
        }
        return ""
    }

    /**
     * Parses expected remaining road distance in meters from strings like
     * "32 min · 34 km", "1 h 10 min • 85,4 km", "18.5 km", or "850 m".
     * Returns 0.0 when unavailable.
     */
    fun parseRemainingDistanceMeters(remainingTimeDistance: String): Double {
        val norm = normalizeSpaces(remainingTimeDistance)
        if (norm.isEmpty()) return 0.0
        val regex = Regex("(?i)(?<![0-9.,])([0-9]+(?:[.,][0-9]+)?)\\s*(km|chilometri|chilometro|m|metri|metro)\\b")
        var bestKmMeters = 0.0
        var bestMeters = 0.0
        for (m in regex.findAll(norm)) {
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2].lowercase(Locale.ITALIAN)
            if (unit == "km" || unit.startsWith("chilometr")) {
                val meters = value * 1000.0
                if (meters > bestKmMeters) bestKmMeters = meters
            } else {
                if (value > bestMeters) bestMeters = value
            }
        }
        return if (bestKmMeters > 0.0) bestKmMeters else bestMeters
    }

    /**
     * Verifies whether a candidate straight-line Haversine distance is physically compatible
     * with the reported remaining road distance (`[0.20x .. 1.15x]` for trips >= 2 km).
     */
    fun isCandidateDistancePlausible(
        straightLineMeters: Double,
        expectedRemainingMeters: Double
    ): Boolean {
        if (straightLineMeters <= 25.0 || straightLineMeters > 2_500_000.0) return false
        if (expectedRemainingMeters >= 2_000.0) {
            val minStraight = expectedRemainingMeters * 0.20
            val maxStraight = expectedRemainingMeters * 1.15
            return straightLineMeters in minStraight..maxStraight
        }
        return true
    }

    private fun recordFailedQuery(queryKey: String) {
        val prevCount = failedQueries[queryKey]?.first ?: 0
        val nextCount = prevCount + 1
        val backoffMs = when {
            nextCount <= 1 -> 60_000L
            nextCount == 2 -> 180_000L
            else -> 600_000L
        }
        failedQueries[queryKey] = Pair(nextCount, System.currentTimeMillis() + backoffMs)
    }

    /**
     * Checks if a new OSRM fallback route fetch is needed based on `destination` changes
     * or deviation from the active route, and dispatches an async fetch if needed.
     */
    fun fetchRouteIfNeeded(
        currentLat: Double,
        currentLon: Double,
        destination: String,
        instruction: String,
        remainingTimeDistance: String = "",
        onNoRoute: (() -> Unit)? = null,
        onResult: (RouteResult) -> Unit
    ) {
        if (currentLat == 0.0 && currentLon == 0.0) return

        val query = extractTargetQuery(destination, instruction)
        if (query.isEmpty()) {
            if (lastRoute != null) {
                lastRoute = null
            }
            onNoRoute?.invoke()
            return
        }

        val expectedRemMeters = parseRemainingDistanceMeters(remainingTimeDistance)
        val cached = lastRoute
        val now = System.currentTimeMillis()
        val queryKey = query.lowercase(Locale.ITALIAN)
        val sameQuery = cached != null && cached.query.equals(query, ignoreCase = true)

        if (sameQuery && cached != null) {
            val minDistToRoute = minDistanceToPolylineMeters(currentLat, currentLon, cached.coordinates)
            if (minDistToRoute < 180.0) {
                onResult(cached)
                return
            }
            Log.i(TAG, "Vehicle deviated ${minDistToRoute.toInt()}m from cached route for '$query', recalculating...")
        }

        val failedEntry = failedQueries[queryKey]
        if (failedEntry != null && now < failedEntry.second) {
            return
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
                Log.w(
                    TAG,
                    "Resolving fallback route from ($currentLat, $currentLon) to dest='$query' " +
                        "(expectedRem=${expectedRemMeters.toInt()}m)"
                )
                val destCoords = geocodeDestination(query, currentLat, currentLon, expectedRemMeters)
                if (destCoords == null) {
                    recordFailedQuery(queryKey)
                    Log.w(TAG, "Could not geocode plausible target for dest='$query' (negative backoff)")
                    mainHandler.post { onNoRoute?.invoke() }
                    return@execute
                }

                val (destLat, destLon) = destCoords
                val distToDest = haversineMeters(currentLat, currentLon, destLat, destLon)
                if (!isCandidateDistancePlausible(distToDest, expectedRemMeters)) {
                    recordFailedQuery(queryKey)
                    Log.w(
                        TAG,
                        "Rejected geocoded target ($destLat, $destLon): haversine=${distToDest.toInt()}m " +
                            "incompatible with expectedRem=${expectedRemMeters.toInt()}m"
                    )
                    mainHandler.post { onNoRoute?.invoke() }
                    return@execute
                }

                val routeResult = fetchOsrmRoute(query, currentLat, currentLon, destLat, destLon)
                if (routeResult != null) {
                    if (expectedRemMeters >= 3_000.0) {
                        val minRoad = expectedRemMeters * 0.45
                        val maxRoad = expectedRemMeters * 2.20
                        if (routeResult.distanceMeters !in minRoad..maxRoad) {
                            recordFailedQuery(queryKey)
                            Log.w(
                                TAG,
                                "Rejected OSRM route for '$query': osrm=${routeResult.distanceMeters.toInt()}m " +
                                    "vs expected=${expectedRemMeters.toInt()}m"
                            )
                            mainHandler.post { onNoRoute?.invoke() }
                            return@execute
                        }
                    }
                    failedQueries.remove(queryKey)
                    lastRoute = routeResult
                    Log.w(
                        TAG,
                        "OSRM fallback route ready for '$query': points=${routeResult.coordinates.size}, " +
                            "summary=${routeResult.formattedSummary}, eta=${routeResult.formattedEta}"
                    )
                    mainHandler.post {
                        onResult(routeResult)
                    }
                } else {
                    recordFailedQuery(queryKey)
                    mainHandler.post { onNoRoute?.invoke() }
                }
            } catch (e: Exception) {
                recordFailedQuery(queryKey)
                Log.w(TAG, "Route fetch failed for '$query': ${e.message}")
            } finally {
                isFetching = false
            }
        }
    }

    private fun scoreCandidate(straightLineMeters: Double, expectedRemMeters: Double): Double {
        return if (expectedRemMeters >= 2_000.0) {
            // Typical road tortuosity factor ~1.28x straight-line distance
            abs((straightLineMeters * 1.28) - expectedRemMeters)
        } else {
            straightLineMeters
        }
    }

    private fun geocodeDestination(
        query: String,
        biasLat: Double,
        biasLon: Double,
        expectedRemMeters: Double
    ): Pair<Double, Double>? {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // 1. Photon (Komoot) with location bias + physical distance sanity filter
        try {
            val photonUrl = "https://photon.komoot.io/api/?q=$encoded&lat=$biasLat&lon=$biasLon&limit=10"
            val body = httpGet(photonUrl, 5000)
            if (!body.isNullOrEmpty()) {
                val json = JSONObject(body)
                val features = json.optJSONArray("features")
                if (features != null && features.length() > 0) {
                    var bestPair: Pair<Double, Double>? = null
                    var bestScore = Double.MAX_VALUE
                    for (i in 0 until features.length()) {
                        val geom = features.getJSONObject(i).optJSONObject("geometry") ?: continue
                        val coords = geom.optJSONArray("coordinates") ?: continue
                        if (coords.length() >= 2) {
                            val lon = coords.getDouble(0)
                            val lat = coords.getDouble(1)
                            val d = haversineMeters(biasLat, biasLon, lat, lon)
                            if (isCandidateDistancePlausible(d, expectedRemMeters)) {
                                val score = scoreCandidate(d, expectedRemMeters)
                                if (score < bestScore) {
                                    bestScore = score
                                    bestPair = Pair(lat, lon)
                                }
                            }
                        }
                    }
                    if (bestPair != null) return bestPair
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Photon geocode error: ${e.message}")
        }

        // 2. Nominatim fallback with dynamic viewbox sized to expected trip distance
        try {
            val delta = if (expectedRemMeters > 25_000.0) {
                ((expectedRemMeters / 111_000.0) * 1.45).coerceIn(0.35, 6.0)
            } else {
                0.35
            }
            val viewbox = "${biasLon - delta},${biasLat + delta},${biasLon + delta},${biasLat - delta}"
            val nomUrl = "https://nominatim.openstreetmap.org/search?format=json&q=$encoded&viewbox=$viewbox&bounded=0&limit=6"
            val body = httpGet(nomUrl, 5000)
            if (!body.isNullOrEmpty() && body.trim().startsWith("[")) {
                val arr = JSONArray(body)
                var bestPair: Pair<Double, Double>? = null
                var bestScore = Double.MAX_VALUE
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val lat = item.optString("lat", "").toDoubleOrNull() ?: continue
                    val lon = item.optString("lon", "").toDoubleOrNull() ?: continue
                    val d = haversineMeters(biasLat, biasLon, lat, lon)
                    if (isCandidateDistancePlausible(d, expectedRemMeters)) {
                        val score = scoreCandidate(d, expectedRemMeters)
                        if (score < bestScore) {
                            bestScore = score
                            bestPair = Pair(lat, lon)
                        }
                    }
                }
                if (bestPair != null) return bestPair
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
                setRequestProperty("User-Agent", "BydClusterNavBridge/1.6.0 (Android Automotive)")
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

    internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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
