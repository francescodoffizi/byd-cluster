package com.byd.clusternavbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.byd.clusternavbridge.model.NavState
import com.byd.clusternavbridge.service.NavClusterService
import java.util.Locale

/**
 * TbtBroadcastReceiver
 *
 * Riceve broadcast Turn-by-Turn da applicazioni connesse o patchate (es. ABRP via AbrpClusterBridge).
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
 */
class TbtBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TbtBroadcastReceiver"
        const val ACTION_TBT_UPDATE = "com.byd.clusternavbridge.TBT_UPDATE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_TBT_UPDATE) return

        val instruction = intent.getStringExtra("instruction") ?: ""
        val roadName = intent.getStringExtra("road_name") ?: ""
        val destination = intent.getStringExtra("destination") ?: ""
        val eta = intent.getStringExtra("eta") ?: ""
        val isActive = intent.getBooleanExtra("is_active", true)

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
        val mergedDest = destination.ifEmpty { if (prev?.sourcePackage == "com.iternio.abrpapp") prev.destination else "" }
        val mergedEta = eta.ifEmpty { if (prev?.sourcePackage == "com.iternio.abrpapp") prev.eta else "" }
        val mergedRemaining = if (prev?.sourcePackage == "com.iternio.abrpapp" && prev.remainingTimeDistance.isNotEmpty()) {
            prev.remainingTimeDistance
        } else if (distanceMeters > 0) {
            formattedDistance
        } else {
            ""
        }

        val navState = NavState(
            isActive = isActive && (fullInstruction.isNotEmpty() || distanceMeters > 0),
            sourcePackage = "com.iternio.abrpapp",
            sourceName = "ABRP",
            distance = formattedDistance,
            instruction = fullInstruction,
            destination = mergedDest,
            eta = mergedEta,
            remainingTimeDistance = mergedRemaining
        )

        // Se il servizio è attivo, aggiorna direttamente lo stato
        val service = NavClusterService.instance
        if (service != null) {
            service.onNavStateChanged(navState)
        } else {
            // Se il servizio non era ancora in esecuzione, avvialo
            NavClusterService.startService(context)
            // Attendi che il servizio si leghi e riprova via handler
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                NavClusterService.instance?.onNavStateChanged(navState)
            }, 500)
        }
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters <= 0 -> ""
            meters < 1000 -> "${meters.toInt()} m"
            else -> String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        }
    }
}
