package com.byd.clusternavbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.byd.clusternavbridge.model.NavState
import java.util.Locale

/**
 * NavAccessibilityService v1.5.9
 *
 * Cattura i banner di svolta real-time direttamente dall'albero UI di HERE WeGo, ABRP,
 * Google Maps, Waze e OsmAnd quando sono in primo piano su Display 0.
 *
 * Include la pulizia semantica specifica per il motore Flutter di HERE WeGo
 * (rimozione di `travel_mode_car`, `schoolZone`, `second_maneuver_action_*` e traduzione
 * di `first_maneuver_action_*` in istruzioni stradali italiane chiare).
 */
class NavAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BydNavA11yService"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastActivePackage: String = ""
            private set

        @Volatile
        var lastActiveUptimeMs: Long = 0L
            private set

        private val GENERIC_STATUS_REGEX = Regex("^navigazione\\b.*\\battiva$", RegexOption.IGNORE_CASE)

        private val NAV_PACKAGES = setOf(
            "com.here.app.maps",
            "com.iternio.abrpapp",
            "com.iternio.abattrplan",
            "com.google.android.apps.maps",
            "app.revanced.android.apps.maps",
            "app.morphe.android.apps.maps.automotive",
            "com.waze",
            "com.sygic.aura",
            "com.tomtom.gplay.navapp",
            "net.osmand",
            "net.osmand.plus",
            "app.organicmaps"
        )

        private val DISTANCE_REGEX = Regex(
            "^\\b(\\d+(?:[.,]\\d+)?\\s*(?:m|km|mi|ft|yd|metri|chilometri))\\b$",
            RegexOption.IGNORE_CASE
        )

        private val INLINE_DISTANCE_REGEX = Regex(
            "\\b(\\d+(?:[.,]\\d+)?\\s*(?:m|km|mi|ft|yd))\\b",
            RegexOption.IGNORE_CASE
        )

        private val TIME_DIST_SUMMARY_REGEX = Regex(
            "(\\d+\\s*(?:h|ora|ore|hr|min|m)\\b.*\\b\\d+(?:[.,]\\d+)?\\s*(?:km|m|mi)\\b|\\b\\d+(?:[.,]\\d+)?\\s*(?:km|m|mi)\\b.*\\d+\\s*(?:h|ora|ore|hr|min|m)\\b)",
            RegexOption.IGNORE_CASE
        )

        private val ETA_REGEX = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b")

        private val IGNORED_UI_STRINGS = setOf(
            "cerca", "search", "menu", "chiudi", "close", "annulla", "cancel",
            "indicazioni", "directions", "avvia", "start", "esci", "exit",
            "riprendi", "resume", "centra", "recenter", "audio", "mute",
            "impostazioni", "settings", "profilo", "profile", "salvati", "saved",
            "preferiti", "favorites", "casa", "home", "lavoro", "work",
            "aggiungi", "add", "condividi", "share", "segnala", "report",
            "carplay", "android auto", "qui", "here", "here wego", "abrp",
            "travel_mode_car", "travel_mode_bicycle", "travel_mode_pedestrian",
            "travel_mode_truck", "travel_mode_transit", "schoolzone", "school_zone",
            "speed_camera", "speedlimit", "speed_limit"
        )

        /**
         * Ripulisce o traduce gli ID semantici grezzi esposti dal motore Flutter di HERE WeGo.
         * Restituisce Pair(testoPulito, isFallbackManeuverAction).
         */
        fun cleanFlutterSemantics(raw: String): Pair<String, Boolean> {
            var text = raw
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isEmpty()) return Pair("", false)

            // Rimuovi token tecnici che Flutter concatena nei contentDescription
            text = text
                .replace(Regex("\\btravel_mode_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\bschool_?zone\\b", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\bsecond_maneuver_action_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE), "")
                .trim()

            if (text.isEmpty()) return Pair("", false)

            val lower = text.lowercase(Locale.ROOT)
            if (lower in IGNORED_UI_STRINGS) return Pair("", false)

            if (lower.startsWith("first_maneuver_action_") || lower.startsWith("maneuver_action_")) {
                val action = lower
                    .removePrefix("first_maneuver_action_")
                    .removePrefix("maneuver_action_")
                val translated = translateManeuverAction(action)
                return Pair(translated, true)
            }

            if (lower.contains("first_maneuver_action_")) {
                val actionMatch = Regex("first_maneuver_action_([a-z0-9_]+)", RegexOption.IGNORE_CASE).find(text)
                val actionLabel = actionMatch?.groupValues?.getOrNull(1)?.let { translateManeuverAction(it) } ?: ""
                val stripped = text.replace(Regex("first_maneuver_action_[a-z0-9_]+", RegexOption.IGNORE_CASE), "").trim()
                return if (stripped.isNotEmpty()) {
                    Pair(stripped, false)
                } else {
                    Pair(actionLabel, true)
                }
            }

            return Pair(text, false)
        }

        private fun translateManeuverAction(action: String): String {
            val a = action.lowercase(Locale.ROOT)
            return when {
                a.contains("right_uturn") || a.contains("uturn_right") -> "Inversione a U a destra"
                a.contains("uturn") || a.contains("u_turn") -> "Inversione a U"
                a.contains("sharp_left") || a.contains("hard_left") -> "Svolta stretta a sinistra"
                a.contains("sharp_right") || a.contains("hard_right") -> "Svolta stretta a destra"
                a.contains("slight_left") || a.contains("keep_left") || a.contains("bear_left") -> "Tieni la sinistra"
                a.contains("slight_right") || a.contains("keep_right") || a.contains("bear_right") -> "Tieni la destra"
                a.contains("left") -> "Svolta a sinistra"
                a.contains("right") -> "Svolta a destra"
                a.contains("roundabout") || a.contains("rotary") -> "Alla rotonda"
                a.contains("exit") -> "Prendi l'uscita"
                a.contains("arrive") || a.contains("destination") -> "Arrivo a destinazione"
                else -> "Prosegui dritto"
            }
        }
    }

    private var lastInspectTimeMs = 0L
    private var lastDispatchedSignature = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        Log.i(TAG, "NavAccessibilityService connected")
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 350L
        info.packageNames = NAV_PACKAGES.toTypedArray()
        serviceInfo = info
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isConnected = false
        Log.w(TAG, "NavAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in NAV_PACKAGES) return

        val now = SystemClock.uptimeMillis()
        if (now - lastInspectTimeMs < 700L) return
        lastInspectTimeMs = now

        val root = resolveTargetRootNode(pkg) ?: return
        try {
            inspectWindowRoot(pkg, root)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect accessibility tree for $pkg: ${e.message}")
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun resolveTargetRootNode(pkg: String): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            val activePkg = activeRoot.packageName?.toString()
            if (activePkg == null || activePkg == pkg) {
                return activeRoot
            }
            try {
                activeRoot.recycle()
            } catch (_: Exception) {}
        }

        try {
            val interactiveWindows = windows
            if (interactiveWindows != null) {
                var matched: AccessibilityNodeInfo? = null
                for (win in interactiveWindows) {
                    val winRoot = win.root ?: continue
                    if (matched == null && winRoot.packageName?.toString() == pkg) {
                        matched = winRoot
                    } else {
                        try {
                            winRoot.recycle()
                        } catch (_: Exception) {}
                    }
                }
                if (matched != null) return matched
            }
        } catch (_: Exception) {}

        return null
    }

    private data class NodeTextItem(
        val text: String,
        val viewId: String,
        val bounds: Rect,
        val isFallbackAction: Boolean = false
    )

    private fun inspectWindowRoot(pkg: String, root: AccessibilityNodeInfo) {
        val items = mutableListOf<NodeTextItem>()
        collectTextNodes(root, items, 0)
        if (items.isEmpty()) return

        items.sortWith(compareBy<NodeTextItem> { it.bounds.top }.thenBy { it.bounds.left })

        var maneuverDist = ""
        val roadNameParts = mutableListOf<String>()
        val fallbackActionParts = mutableListOf<String>()
        var summaryText = ""
        var etaText = ""

        val topBannerItems = items.filter { it.bounds.top in 0..460 && it.bounds.bottom > 0 }
        val bottomItems = items.filter { it.bounds.top > 460 }

        // Pass 1: individua la distanza della prossima svolta nel banner superiore
        var firstDistTop = -1
        var firstDistItem: NodeTextItem? = null
        for (item in topBannerItems) {
            val t = item.text
            val exactDist = DISTANCE_REGEX.find(t)
            if (exactDist != null) {
                maneuverDist = exactDist.groupValues[1].trim()
                firstDistTop = item.bounds.top
                firstDistItem = item
                break
            }
        }

        if (maneuverDist.isEmpty()) {
            for (item in topBannerItems) {
                val t = item.text
                if (TIME_DIST_SUMMARY_REGEX.containsMatchIn(t)) continue
                val inlineDist = INLINE_DISTANCE_REGEX.find(t)
                if (inlineDist != null) {
                    maneuverDist = inlineDist.groupValues[1].trim()
                    firstDistTop = item.bounds.top
                    firstDistItem = item
                    val remainder = t.replace(inlineDist.value, "")
                        .trim(' ', '-', '–', '—', ',', ':', '·', '•')
                    if (remainder.length >= 2 && !isIgnoredToken(remainder)) {
                        roadNameParts.add(remainder)
                    }
                    break
                }
            }
        }

        if (maneuverDist.isEmpty()) {
            return
        }

        // Pass 2: raccogli i nomi delle vie e le azioni di manovra nel banner principale
        // (inclusi i nodi posizionati leggermente sopra la distanza, es. firstDistTop - 110)
        for (item in topBannerItems) {
            if (item === firstDistItem) continue
            val t = item.text
            if (TIME_DIST_SUMMARY_REGEX.containsMatchIn(t)) {
                if (summaryText.isEmpty()) summaryText = t
                continue
            }
            if (DISTANCE_REGEX.matches(t)) {
                continue
            }
            if (item.bounds.top in (firstDistTop - 110)..(firstDistTop + 175)) {
                if (!isIgnoredToken(t) && t.length >= 2) {
                    if (item.isFallbackAction) {
                        if (!fallbackActionParts.contains(t)) fallbackActionParts.add(t)
                    } else {
                        if (!roadNameParts.contains(t)) roadNameParts.add(t)
                    }
                }
            }
        }

        // Pass 3: barra inferiore per ETA e riepilogo tempo/distanza residua
        val bottomTexts = mutableListOf<String>()
        for (item in bottomItems) {
            val t = item.text
            bottomTexts.add(t)
            if (summaryText.isEmpty() && TIME_DIST_SUMMARY_REGEX.containsMatchIn(t)) {
                summaryText = t
            }
            if (etaText.isEmpty()) {
                val etaMatch = ETA_REGEX.find(t)
                if (etaMatch != null) {
                    etaText = etaMatch.groupValues[0]
                }
            }
        }

        if (summaryText.isEmpty() && bottomTexts.isNotEmpty()) {
            val joinedBottom = bottomTexts.joinToString(" • ")
            val m = TIME_DIST_SUMMARY_REGEX.find(joinedBottom)
            if (m != null) {
                summaryText = m.groupValues[1]
            } else {
                val distNode = bottomTexts.firstOrNull { DISTANCE_REGEX.matches(it) }
                val minNode = bottomTexts.firstOrNull {
                    Regex("^\\d+\\s*(?:min|h|ora|ore)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
                }
                if (minNode != null && distNode != null) {
                    summaryText = "$minNode • $distNode"
                } else if (distNode != null) {
                    summaryText = distNode
                }
            }
        }

        val instruction = when {
            roadNameParts.isNotEmpty() && fallbackActionParts.isNotEmpty() -> {
                val road = roadNameParts.take(2).joinToString(" - ")
                val action = fallbackActionParts.first()
                if (road.lowercase(Locale.ITALIAN).contains(action.lowercase(Locale.ITALIAN))) {
                    road
                } else {
                    "$action su $road"
                }
            }
            roadNameParts.isNotEmpty() -> roadNameParts.take(2).joinToString(" - ")
            fallbackActionParts.isNotEmpty() -> fallbackActionParts.first()
            else -> "Segui il percorso"
        }

        lastActivePackage = pkg
        lastActiveUptimeMs = SystemClock.uptimeMillis()

        val prevState = NavClusterService.instance?.currentNavState
        val preservedDest = if (prevState != null && prevState.isActive && prevState.sourcePackage == pkg) {
            prevState.destination.takeIf {
                it.isNotBlank() &&
                    !GENERIC_STATUS_REGEX.matches(it.trim()) &&
                    !com.byd.clusternavbridge.route.RouteFetcher.isNonGeocodablePlaceholder(it)
            } ?: ""
        } else {
            ""
        }

        val destination = preservedDest
        val effectiveSummary = summaryText.ifBlank {
            if (prevState != null && prevState.isActive && prevState.sourcePackage == pkg) {
                prevState.remainingTimeDistance
            } else {
                ""
            }
        }
        val effectiveEta = etaText.ifBlank {
            if (prevState != null && prevState.isActive && prevState.sourcePackage == pkg) {
                prevState.eta
            } else {
                ""
            }
        }

        val signature = "$pkg|$maneuverDist|$instruction|$effectiveSummary|$effectiveEta"
        if (signature == lastDispatchedSignature) return
        lastDispatchedSignature = signature

        val appName = when (pkg) {
            "com.here.app.maps" -> "HERE WeGo"
            "com.iternio.abrpapp", "com.iternio.abattrplan" -> "ABRP"
            "com.google.android.apps.maps", "app.revanced.android.apps.maps" -> "Google Maps"
            "com.waze" -> "Waze"
            "net.osmand", "net.osmand.plus" -> "OsmAnd"
            else -> "Nav"
        }

        Log.w(
            TAG,
            "Parsed A11y NavState ($appName): dist='$maneuverDist', inst='$instruction', " +
                "dest='$destination', rem='$effectiveSummary', eta='$effectiveEta'"
        )

        val state = NavState(
            isActive = true,
            sourcePackage = pkg,
            sourceName = appName,
            distance = maneuverDist,
            instruction = instruction,
            destination = destination,
            eta = effectiveEta,
            remainingTimeDistance = effectiveSummary
        )

        if (NavClusterService.instance == null) {
            NavClusterService.startService(this)
        }
        NavClusterService.instance?.onNavStateChanged(state)
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<NodeTextItem>,
        depth: Int
    ) {
        if (node == null || depth > 28) return
        if (!node.isVisibleToUser) return

        val rawText = node.text?.toString()?.trim()
        val rawDesc = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName ?: ""

        val candidates = listOfNotNull(
            rawText?.takeIf { it.isNotEmpty() },
            rawDesc?.takeIf { it.isNotEmpty() && it != rawText }
        )

        for (candidate in candidates) {
            val (cleaned, isFallback) = cleanFlutterSemantics(candidate)
            if (cleaned.isNotEmpty() && cleaned.length <= 120) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    out.add(NodeTextItem(cleaned, viewId, rect, isFallback))
                }
            }
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            try {
                collectTextNodes(child, out, depth + 1)
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {}
            }
        }
    }

    private fun isIgnoredToken(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower in IGNORED_UI_STRINGS) return true
        if (lower.matches(Regex("^\\d+\\s*(?:km/h|mph)$"))) return true
        if (lower.matches(Regex("^\\d+$"))) return true
        if (lower.startsWith("travel_mode_") || lower.startsWith("second_maneuver_action_")) return true
        return false
    }

    override fun onInterrupt() {}
}
