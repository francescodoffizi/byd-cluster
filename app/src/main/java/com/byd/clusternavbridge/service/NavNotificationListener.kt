package com.byd.clusternavbridge.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import com.byd.clusternavbridge.model.NavState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap

class NavNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "BydNavNotifListener"

        val TARGET_PACKAGES = setOf(
            "app.revanced.android.apps.maps",
            "com.google.android.apps.maps",
            "com.iternio.abrpapp",
            "com.here.app.maps",
            "net.osmand",
            "net.osmand.plus"
        )
    }

    private val listenerJob = SupervisorJob()
    private val listenerScope = CoroutineScope(Dispatchers.Default + listenerJob)

    override fun onDestroy() {
        super.onDestroy()
        listenerScope.cancel()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.w(TAG, "NotificationListener connected! Ensuring NavClusterService is active...")
        if (NavClusterService.instance == null) {
            NavClusterService.startService(this)
        }
        listenerScope.launch {
            checkActiveNotifications()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        if (sbn.packageName in TARGET_PACKAGES) {
            if (NavClusterService.instance == null) {
                NavClusterService.startService(this)
            }
            listenerScope.launch {
                processNotification(sbn)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        if (sbn.packageName in TARGET_PACKAGES) {
            Log.i(TAG, "Nav notification removed from ${sbn.packageName}")
            listenerScope.launch {
                val activeState = NavClusterService.instance?.currentNavState
                if (activeState?.sourcePackage == sbn.packageName) {
                    // Check if another target package is still navigating
                    checkActiveNotifications()
                }
            }
        }
    }

    private fun checkActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            var foundActive = false
            for (sbn in active) {
                if (sbn.packageName in TARGET_PACKAGES) {
                    if (processNotification(sbn)) {
                        foundActive = true
                        break
                    }
                }
            }
            if (!foundActive) {
                NavClusterService.instance?.onNavStateChanged(NavState(isActive = false))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active notifications: ${e.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val pkg = sbn.packageName

        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!isOngoing) {
            return false
        }

        val extras: Bundle = notification.extras ?: return false
        var title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim() ?: ""
        var text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        val isAbrp = (pkg == "com.iternio.abrpapp")
        val isHereMaps = (pkg == "com.here.app.maps")
        val appName = when (pkg) {
            "app.revanced.android.apps.maps" -> "Google Maps"
            "com.google.android.apps.maps" -> "Google Maps"
            "com.iternio.abrpapp" -> "ABRP"
            "com.here.app.maps" -> "HERE WeGo"
            "net.osmand", "net.osmand.plus" -> "OsmAnd"
            else -> "Nav"
        }

        val distPatternHelper = Regex("^[~]?[\\s]*[0-9]+(?:[.,][0-9]+)?\\s*(m|km|mi|ft)\\.?$", RegexOption.IGNORE_CASE)
        val etaPatternHelper = Regex("^(?:[01]?[0-9]|2[0-3]):[0-5][0-9]$")
        val arrivalPatternHelper = Regex("(?i)(?:arrivo|eta|arrival)\\s*([0-9]{1,2}[:.][0-9]{2})")
        val remainingPatternHelper = Regex("(?i)(\\d+\\s*(?:min|h|ore|km)\\b.*[•·].*|.*[•·].*\\b(?:min|km)\\b|\\d+\\s*min\\b)")
        val batteryPatternHelper = Regex("^[0-9]{1,3}\\s*%$")

        var distance = ""
        var instruction = ""
        var destination = ""
        var eta = extras.getString("byd_extracted_eta", "")
        var remaining = extras.getString("byd_extracted_remaining", "")

        val allViews = listOfNotNull(
            notification.bigContentView,
            notification.contentView,
            notification.headsUpContentView
        )

        // Se è ABRP/HERE, o se title/text sono vuoti o rappresentano solo una destinazione, estraiamo da RemoteViews
        val shouldInspectRemoteViews = isAbrp || isHereMaps || (title.isEmpty() && text.isEmpty()) || 
                (allViews.isNotEmpty() && isDestinationOrRouteSummary(title))

        if (shouldInspectRemoteViews && allViews.isNotEmpty()) {
            val extracted = mutableListOf<String>()
            for (rv in allViews) {
                val viewsTexts = extractTextsFromRemoteViews(rv, pkg)
                for (s in viewsTexts) {
                    if (!extracted.contains(s)) extracted.add(s)
                }
            }
            Log.w(TAG, "[$appName] Extracted ${extracted.size} strings from RemoteViews: $extracted")

            if (extracted.isNotEmpty()) {
                val distCandidates = mutableListOf<String>()
                val candidateManeuvers = mutableListOf<String>()
                val candidateRoadNames = mutableListOf<String>()

                for (item in extracted) {
                    val arrivalMatch = arrivalPatternHelper.find(item)
                    when {
                        batteryPatternHelper.matches(item) -> { /* Skip battery */ }
                        isGenericStatus(item) -> { /* Skip generic status */ }
                        arrivalMatch != null && eta.isEmpty() -> eta = arrivalMatch.groupValues[1]
                        etaPatternHelper.matches(item) && eta.isEmpty() -> eta = item
                        remainingPatternHelper.matches(item) && remaining.isEmpty() -> remaining = item
                        isDestinationOrRouteSummary(item) -> {
                            if (destination.isEmpty()) destination = item
                        }
                        distPatternHelper.matches(item) -> {
                            distCandidates.add(item)
                        }
                        isManeuverVerb(item) -> {
                            if (!candidateManeuvers.contains(item)) candidateManeuvers.add(item)
                        }
                        else -> {
                            if (item.length >= 2 && !candidateRoadNames.contains(item)) {
                                candidateRoadNames.add(item)
                            }
                        }
                    }
                }

                // Identifica la distanza di manovra rispetto alla distanza di viaggio
                if (distCandidates.isNotEmpty()) {
                    // Cerca prima distanze in metri (es. "300 m") - sono sicuramente di manovra
                    val meterDist = distCandidates.firstOrNull { it.contains("m", ignoreCase = true) && !it.contains("km", ignoreCase = true) }
                    if (meterDist != null) {
                        distance = meterDist
                        // Qualsiasi altra distanza in km può essere la distanza totale
                        val totalDist = distCandidates.firstOrNull { it.contains("km", ignoreCase = true) }
                        if (totalDist != null && remaining.isEmpty()) {
                            remaining = totalDist
                        }
                    } else {
                        // Tutte in km: ordina per valore numerico crescente (la più piccola è la svolta, la più grande è il viaggio)
                        val sortedDist = distCandidates.sortedBy { parseDistanceStringToMeters(it) }
                        distance = sortedDist.first()
                        if (sortedDist.size > 1 && remaining.isEmpty()) {
                            remaining = sortedDist.last()
                        }
                    }
                }

                // Costruisci l'istruzione di manovra
                if (candidateManeuvers.isNotEmpty()) {
                    val maneuver = candidateManeuvers.joinToString(" ")
                    instruction = if (candidateRoadNames.isNotEmpty()) {
                        "$maneuver su ${candidateRoadNames.first()}"
                    } else {
                        maneuver
                    }
                } else if (distCandidates.isNotEmpty() && candidateRoadNames.isNotEmpty()) {
                    instruction = candidateRoadNames.joinToString(" - ")
                } else if (distance.isNotEmpty() && !distance.endsWith("km", ignoreCase = true)) {
                    // C'è una distanza di manovra in metri ma nessun testo: dritto
                    instruction = "Prosegui dritto"
                }
            }
        }

        // Supporto formato combinato "350 m • Via Roma" o "350 m - Svolta a destra" (tipico di HERE WeGo e OsmAnd)
        val combinedDistPrefixRegex = Regex("^([~]?[0-9]+(?:[.,][0-9]+)?\\s*(?:m|km|mi|ft))\\s*[-•·:]\\s*(.+)$", RegexOption.IGNORE_CASE)
        if (title.isNotEmpty()) {
            val combinedMatch = combinedDistPrefixRegex.find(title)
            if (combinedMatch != null) {
                if (distance.isEmpty()) distance = combinedMatch.groupValues[1].trim()
                if (instruction.isEmpty()) instruction = combinedMatch.groupValues[2].trim()
            }
        }
        if (text.isNotEmpty() && (distance.isEmpty() || instruction.isEmpty())) {
            val combinedMatch = combinedDistPrefixRegex.find(text)
            if (combinedMatch != null) {
                if (distance.isEmpty()) distance = combinedMatch.groupValues[1].trim()
                if (instruction.isEmpty()) instruction = combinedMatch.groupValues[2].trim()
            }
        }

        // Fallback su title e text se instruction o distance sono ancora vuoti (es. Google Maps / HERE WeGo standard)
        if (instruction.isEmpty() && title.isNotEmpty() && !isGenericStatus(title)) {
            if (isDestinationOrRouteSummary(title)) {
                if (destination.isEmpty()) destination = title
            } else if (distPatternHelper.matches(title)) {
                if (distance.isEmpty()) distance = title
                if (instruction.isEmpty() && !isDestinationOrRouteSummary(text) && !isGenericStatus(text)) {
                    instruction = text
                }
            } else {
                if (instruction.isEmpty()) instruction = title
            }
        }

        if (instruction.isEmpty() && text.isNotEmpty() && !isGenericStatus(text)) {
            if (isDestinationOrRouteSummary(text)) {
                if (destination.isEmpty()) destination = text
            } else if (distPatternHelper.matches(text)) {
                if (distance.isEmpty()) distance = text
            } else {
                instruction = text
            }
        } else if (distance.isEmpty() && text.isNotEmpty() && distPatternHelper.matches(text)) {
            distance = text
        }

        if (subText.isNotEmpty() && remaining.isEmpty() && !isGenericStatus(subText)) {
            remaining = subText
            val arrivalMatch = arrivalPatternHelper.find(subText)
            if (arrivalMatch != null && eta.isEmpty()) {
                eta = arrivalMatch.groupValues[1]
            }
        }

        // Se la notifica contiene solo un riepilogo destinazione (es. LiveUpdate Voltra di ABRP o Foreground HERE WeGo),
        // non sovrascrivere un'istruzione di svolta attiva già ricevuta via broadcast TbtBroadcastReceiver o UI Banner!
        val currentState = NavClusterService.instance?.currentNavState
        if (instruction.isEmpty() && currentState != null && currentState.isActive &&
            currentState.sourcePackage == pkg && currentState.instruction.isNotEmpty() &&
            !isGenericStatus(currentState.instruction)
        ) {
            instruction = currentState.instruction
            if (distance.isEmpty()) distance = currentState.distance
        }

        // Se la notifica è ongoing su un canale di guida attivo (es. HERE WeGo in primo piano con "Navigazione vocale"),
        // mantieni la sessione attiva con un'etichetta di destinazione pulita in attesa della prima manovra.
        if (instruction.isEmpty() && destination.isEmpty() && distance.isEmpty() &&
            (isHereMaps || isAbrp) && (isGenericStatus(title) || isGenericStatus(text))
        ) {
            destination = "Navigazione $appName attiva"
        }

        // Se non abbiamo ancora un'istruzione di svolta ma abbiamo una destinazione e siamo attivi
        if (instruction.isEmpty() && destination.isNotEmpty()) {
            Log.d(TAG, "[$appName] Route active towards '$destination' (no active turn maneuver yet)")
        }

        if (instruction.isEmpty() && destination.isEmpty() && distance.isEmpty()) {
            Log.d(TAG, "[$appName] Ignored empty nav state")
            return false
        }

        Log.w(TAG, "[$appName] NavParsed -> Instr: '$instruction' | Dist: '$distance' | Dest: '$destination' | Rem: '$remaining' | ETA: '$eta'")

        val state = NavState(
            isActive = true,
            sourcePackage = pkg,
            sourceName = appName,
            distance = distance,
            instruction = instruction,
            destination = destination,
            eta = eta,
            remainingTimeDistance = remaining
        )

        NavClusterService.instance?.onNavStateChanged(state)
        return true
    }

    private fun isDestinationOrRouteSummary(str: String): Boolean {
        val lower = str.lowercase(java.util.Locale.ITALIAN).trim()
        if (lower.isEmpty()) return false
        val isDest = lower.startsWith("verso ") ||
                lower.startsWith("viaggio verso ") ||
                lower.startsWith("viaggio per ") ||
                lower.startsWith("trip to ") ||
                lower.startsWith("direzione ") ||
                lower.startsWith("destinazione") ||
                lower.startsWith("rotta verso") ||
                lower.startsWith("arrivo a ") ||
                lower.startsWith("arrivo ") ||
                lower.startsWith("arriving at ") ||
                lower == "casa" || lower == "lavoro" || lower == "home" || lower == "work"
        if (!isDest) return false
        val hasManeuver = listOf(
            "svolta", "gira", "prendi", "esci", "uscita", "rotonda", "rotatoria",
            "tieni", "rimani", "continua", "prosegui", "destra", "sinistra"
        ).any { lower.contains(it) }
        return !hasManeuver
    }

    private fun isManeuverVerb(str: String): Boolean {
        val lower = str.lowercase(java.util.Locale.ITALIAN).trim()
        return listOf(
            "svolta", "gira", "prendi", "esci", "uscita", "rotonda", "rotatoria",
            "tieni", "rimani", "continua", "prosegui", "inversione", "dritto",
            "destra", "sinistra", "corsia", "rampa", "biforcazione",
            "turn", "exit", "keep", "roundabout", "continue", "right", "left"
        ).any { lower.contains(it) }
    }

    private fun parseDistanceStringToMeters(str: String): Int {
        return try {
            val clean = str.lowercase(java.util.Locale.ITALIAN).trim()
            if (clean.contains("km")) {
                val num = clean.replace("km", "").replace(",", ".").trim().toFloatOrNull() ?: 1.0f
                (num * 1000).toInt()
            } else {
                clean.replace("m", "").replace(".", "").trim().toIntOrNull() ?: 500
            }
        } catch (_: Exception) {
            500
        }
    }

    private fun extractTextsFromRemoteViews(rv: RemoteViews?, pkg: String, depth: Int = 0): List<String> {
        if (rv == null || depth > 2) return emptyList()
        val texts = mutableListOf<String>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        visited.add(rv)

        // 1. Reflection chirurgica sui campi mActions del RemoteViews
        try {
            var currentClass: Class<*>? = rv.javaClass
            var mActionsField: java.lang.reflect.Field? = null
            while (currentClass != null && mActionsField == null) {
                try {
                    mActionsField = currentClass.getDeclaredField("mActions")
                } catch (_: NoSuchFieldException) {
                    currentClass = currentClass.superclass
                }
            }

            if (mActionsField != null) {
                mActionsField.isAccessible = true
                val actions = mActionsField.get(rv) as? List<*>
                if (actions != null) {
                    for (action in actions) {
                        action ?: continue
                        if (!visited.add(action)) continue
                        inspectAction(action, pkg, depth + 1, texts, visited)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "extractTextsFromRemoteViews reflection error: ${e.message}")
        }

        // 2. Fallback: inflazione reale in memoria con il package context corretto solo se depth == 0 e nessun testo trovato
        if (texts.isEmpty() && depth == 0) {
            try {
                val targetContext = try {
                    createPackageContext(pkg, android.content.Context.CONTEXT_RESTRICTED or android.content.Context.CONTEXT_IGNORE_SECURITY)
                } catch (_: Exception) {
                    this
                }
                val view = rv.apply(targetContext, null)
                extractTextsFromView(view, texts)
            } catch (e: Exception) {
                Log.d(TAG, "extractTextsFromRemoteViews apply failed: ${e.message}")
            }
        }

        return texts
    }

    private fun inspectAction(action: Any, pkg: String, depth: Int, out: MutableList<String>, visited: MutableSet<Any>) {
        if (depth > 2) return
        val actionClass = action.javaClass
        val className = actionClass.simpleName

        // A. Gestione mirata ReflectionAction (setText, setContentDescription, ecc.)
        if (className.contains("ReflectionAction")) {
            try {
                var methodField: java.lang.reflect.Field? = null
                var valueField: java.lang.reflect.Field? = null
                var c: Class<*>? = actionClass
                while (c != null && (methodField == null || valueField == null)) {
                    if (methodField == null) {
                        try { methodField = c.getDeclaredField("methodName") } catch (_: Exception) {}
                    }
                    if (valueField == null) {
                        try { valueField = c.getDeclaredField("value") } catch (_: Exception) {}
                    }
                    c = c.superclass
                }

                val methodName = methodField?.apply { isAccessible = true }?.get(action) as? String
                if (methodName == "setText" || methodName == "setContentDescription") {
                    val value = valueField?.apply { isAccessible = true }?.get(action)
                    if (value is CharSequence) {
                        val str = value.toString().trim()
                        if (isValidUserText(str) && !out.contains(str)) {
                            out.add(str)
                        }
                    }
                }
            } catch (_: Exception) {}
            return
        }

        // B. Gestione mirata ViewGroupActionAdd (aggiunta di RemoteViews figli)
        if (className.contains("ViewGroupActionAdd")) {
            try {
                var nestedField: java.lang.reflect.Field? = null
                var c: Class<*>? = actionClass
                while (c != null && nestedField == null) {
                    try { nestedField = c.getDeclaredField("mNestedViews") } catch (_: Exception) {}
                    c = c.superclass
                }
                val nestedRv = nestedField?.apply { isAccessible = true }?.get(action) as? RemoteViews
                if (nestedRv != null && visited.add(nestedRv)) {
                    val nestedTexts = extractTextsFromRemoteViews(nestedRv, pkg, depth + 1)
                    for (s in nestedTexts) {
                        if (!out.contains(s)) out.add(s)
                    }
                }
            } catch (_: Exception) {}
            return
        }

        // C. Fallback controllato per altre Action: blocco categorico di puntatori outer-class (this$0) e campi sintetici
        var clazz: Class<*>? = actionClass
        while (clazz != null && clazz != Any::class.java) {
            for (f in clazz.declaredFields) {
                val name = f.name
                if (f.isSynthetic || name.startsWith("this$") || name == "mActions" ||
                    name == "methodName" || name == "mPackage" || name == "packageName" ||
                    name == "bitmap" || name == "mBitmap" || name == "intent" || name == "mIntent") {
                    continue
                }
                try {
                    f.isAccessible = true
                    val value = f.get(action) ?: continue
                    if (!visited.add(value)) continue
                    when (value) {
                        is RemoteViews -> {
                            val nested = extractTextsFromRemoteViews(value, pkg, depth + 1)
                            for (s in nested) {
                                if (!out.contains(s)) out.add(s)
                            }
                        }
                        is CharSequence -> {
                            val str = value.toString().trim()
                            if (isValidUserText(str) && !out.contains(str)) {
                                out.add(str)
                            }
                        }
                        is Collection<*> -> {
                            for (item in value) {
                                item ?: continue
                                if (!visited.add(item)) continue
                                if (item is RemoteViews) {
                                    val nested = extractTextsFromRemoteViews(item, pkg, depth + 1)
                                    for (s in nested) {
                                        if (!out.contains(s)) out.add(s)
                                    }
                                } else if (item is CharSequence) {
                                    val str = item.toString().trim()
                                    if (isValidUserText(str) && !out.contains(str)) {
                                        out.add(str)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            clazz = clazz.superclass
        }
    }

    private fun extractTextsFromView(view: View, out: MutableList<String>) {
        if (view is TextView) {
            val t = view.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && isValidUserText(t) && !out.contains(t)) {
                out.add(t)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                extractTextsFromView(view.getChildAt(i), out)
            }
        }
    }

    private fun isValidUserText(str: String): Boolean {
        if (str.isEmpty() || str.length > 200) return false
        // Escludi nomi di metodi interni Android o azioni Reflection
        if (str.startsWith("set") && str.length > 3 && str[3].isUpperCase()) return false
        if (str.startsWith("android.") || str.startsWith("com.android.") || str.startsWith("androidx.")) return false
        if (str.equals("CharSequence", ignoreCase = true) || str.equals("String", ignoreCase = true)) return false
        // Escludi pulsanti di notifica standard come 'Fine della navigazione'
        val lower = str.lowercase()
        if (lower == "fine della navigazione" || lower == "interrompi" || lower == "termina" || lower == "chiudi") return false
        return true
    }

    private fun isGenericStatus(text: String): Boolean {
        val lower = text.lowercase(java.util.Locale.ITALIAN).trim()
        return lower == "abrp" ||
                lower == "a better routeplanner" ||
                lower == "here wego" ||
                lower == "google maps" ||
                lower == "maps" ||
                lower == "osmand" ||
                lower == "navigazione" ||
                lower == "navigazione attiva" ||
                lower == "navigazione vocale" ||
                lower.contains("navigazione in corso") ||
                lower.contains("tocca per tornare") ||
                lower.contains("tap to return") ||
                lower.contains("ricalcolo") ||
                lower.contains("ricerca segnale") ||
                lower.contains("in attesa di segnale") ||
                lower.contains("in attesa del segnale")
    }
}
