package com.byd.clusternavbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.route.RouteFetcher
import com.byd.clusternavbridge.service.NavClusterService
import java.util.Locale

/**
 * TbtBroadcastReceiver
 *
 * Riceve broadcast Turn-by-Turn e geometria percorso 1:1 da applicazioni connesse o patchate
 * (es. ABRP via AbrpClusterBridge + AbrpRouteBridge).
 * Intent Action: "com.byd.clusternavbridge.TBT_UPDATE"
 *
 * Extra supportati:
 * - instruction (String): "Svolta a destra in Via Roma"
 * - distance_meters (Double/Float/Long/Int): Distanza in metri alla manovra
 * - road_name (String): Nome strada
 * - maneuver_type (Int): Ordinale manovra
 * - destination (String): Destinazione finale
 * - eta (String): ETA stimata
 * - is_active (Boolean): True se in navigazione, False se conclusa
 * - route_geojson_gzip (ByteArray): FeatureCollection GeoJSON 1:1 compressa GZIP
 * - route_geojson (String): FeatureCollection GeoJSON 1:1 non compressa (opzionale/debug)
 * - route_length_meters (Double): Lunghezza complessiva percorso in metri
 * - route_clear (Boolean): True quando il ShapeSource del percorso attivo viene svuotato/rimosso
 * - route_only (Boolean): True quando il broadcast trasporta solo geometria percorso senza svolta
 */
class TbtBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TbtBroadcastReceiver"
        const val ACTION_TBT_UPDATE = "com.byd.clusternavbridge.TBT_UPDATE"
        private const val ABRP_PKG = "com.iternio.abrpapp"

        @Volatile
        private var lastSignature: Int = 0

        @Volatile
        private var lastReceivedUptimeMs: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_TBT_UPDATE) return

        val sourcePkg = intent.getStringExtra("source_package")?.ifBlank { ABRP_PKG } ?: ABRP_PKG
        val routeOnly = intent.getBooleanExtra("route_only", false)
        val routeClear = intent.getBooleanExtra("route_clear", false)
        val gzipRoute = intent.getByteArrayExtra("route_geojson_gzip")
        val plainRoute = intent.getStringExtra("route_geojson")
        val routeLenMeters = intent.getDoubleExtra("route_length_meters", 0.0)

        val instruction = intent.getStringExtra("instruction") ?: ""
        val roadName = intent.getStringExtra("road_name") ?: ""
        val destination = intent.getStringExtra("destination") ?: ""
        val eta = intent.getStringExtra("eta") ?: ""
        val isActive = intent.getBooleanExtra("is_active", true)

        // Deduplica rapida (80ms) nel caso in cui un broadcast esplicito raggiunga sia il receiver statico che quello dinamico
        val sig = buildSignature(sourcePkg, routeOnly, routeClear, gzipRoute?.size ?: plainRoute?.length ?: 0, instruction, roadName, isActive)
        val nowUptime = SystemClock.uptimeMillis()
        if (sig == lastSignature && (nowUptime - lastReceivedUptimeMs) < 80L) {
            return
        }
        lastSignature = sig
        lastReceivedUptimeMs = nowUptime

        if (routeClear) {
            Log.w(TAG, "Ricevuto route_clear per $sourcePkg")
            RouteFetcher.clearAuthoritativeRoute(sourcePkg)
            NavClusterService.instance?.onAuthoritativeRouteUpdated(sourcePkg, null)
            if (routeOnly) return
        }

        val resolvedGeoJson = when {
            gzipRoute != null && gzipRoute.isNotEmpty() -> RouteFetcher.decompressGzipGeoJson(gzipRoute)
            !plainRoute.isNullOrBlank() && plainRoute.trim().startsWith("{") -> plainRoute.trim()
            else -> null
        }

        if (resolvedGeoJson != null) {
            RouteFetcher.setAuthoritativeRoute(sourcePkg, resolvedGeoJson, routeLenMeters)
            Log.w(
                TAG,
                "Ricevuto itinerario 1:1 autorevole per $sourcePkg: json=${resolvedGeoJson.length}B " +
                    "(gzip=${gzipRoute?.size ?: 0}B, len=${routeLenMeters.toInt()}m)"
            )
            val svc = NavClusterService.instance
            if (svc != null) {
                svc.onAuthoritativeRouteUpdated(sourcePkg, resolvedGeoJson)
            } else {
                NavClusterService.startService(context)
                Handler(Looper.getMainLooper()).postDelayed({
                    NavClusterService.instance?.onAuthoritativeRouteUpdated(sourcePkg, resolvedGeoJson)
                }, 450L)
            }
            if (routeOnly) {
                return
            }
        } else if (routeOnly) {
            return
        }

        var distanceMeters: Double = when {
            intent.hasExtra("distance_meters") -> {
                val obj = intent.extras?.get("distance_meters")
                when (obj) {
                    is Double -> obj
                    is Float -> obj.toDouble()
                    is Long -> obj.toDouble()
                    is Int -> obj.toDouble()
                    is String -> obj.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }
            else -> 0.0
        }

        // Se distance_meters non è valorizzato (es. da hook TTS ABRP "Tra 300 metri, svolta a destra..."),
        // estrai la distanza direttamente dal testo dell'istruzione.
        if (distanceMeters <= 0.0 && instruction.isNotEmpty()) {
            val match = Regex("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(chilometri|chilometro|km|metri|metro|m)\\b").find(instruction)
            if (match != null) {
                val num = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
                val unit = match.groupValues[2].lowercase(Locale.ITALIAN)
                distanceMeters = if (unit.startsWith("chilometr") || unit == "km") num * 1000.0 else num
            }
        }

        val formattedDistance = formatDistance(distanceMeters)

        Log.w(TAG, "Ricevuto TBT_UPDATE: instr='$instruction', dist=$formattedDistance ($distanceMeters m), road='$roadName', active=$isActive")

        val fullInstruction = when {
            roadName.isNotEmpty() && !instruction.contains(roadName, ignoreCase = true) -> {
                if (instruction.isNotEmpty()) "$instruction su $roadName" else roadName
            }
            else -> instruction
        }

        val prev = NavClusterService.instance?.currentNavState
        val mergedDest = destination.ifEmpty { if (prev?.sourcePackage == sourcePkg) prev.destination else "" }
        val mergedEta = eta.ifEmpty { if (prev?.sourcePackage == sourcePkg) prev.eta else "" }
        val mergedRemaining = if (prev?.sourcePackage == sourcePkg && prev.remainingTimeDistance.isNotEmpty()) {
            prev.remainingTimeDistance
        } else if (distanceMeters > 0) {
            formattedDistance
        } else {
            ""
        }

        val navState = NavState(
            isActive = isActive && (fullInstruction.isNotEmpty() || distanceMeters > 0),
            sourcePackage = sourcePkg,
            sourceName = if (sourcePkg == ABRP_PKG) "ABRP" else sourcePkg,
            distance = formattedDistance,
            instruction = fullInstruction,
            destination = mergedDest,
            eta = mergedEta,
            remainingTimeDistance = mergedRemaining
        )

        val service = NavClusterService.instance
        if (service != null) {
            service.onNavStateChanged(navState)
        } else {
            NavClusterService.startService(context)
            Handler(Looper.getMainLooper()).postDelayed({
                NavClusterService.instance?.onAuthoritativeRouteUpdated(sourcePkg, RouteFetcher.getAuthoritativeRoute(sourcePkg)?.geoJson)
                NavClusterService.instance?.onNavStateChanged(navState)
            }, 500)
        }
    }

    private fun buildSignature(
        pkg: String,
        routeOnly: Boolean,
        routeClear: Boolean,
        payloadLen: Int,
        instr: String,
        road: String,
        active: Boolean
    ): Int {
        var result = pkg.hashCode()
        result = 31 * result + routeOnly.hashCode()
        result = 31 * result + routeClear.hashCode()
        result = 31 * result + payloadLen
        result = 31 * result + instr.hashCode()
        result = 31 * result + road.hashCode()
        result = 31 * result + active.hashCode()
        return result
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters <= 0 -> ""
            meters < 1000 -> "${meters.toInt()} m"
            else -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        }
    }
}
