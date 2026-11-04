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

class NavNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "BydNavNotifListener"

        val TARGET_PACKAGES = setOf(
            "app.revanced.android.apps.maps",
            "com.google.android.apps.maps",
            "com.iternio.abrpapp"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected! Ensuring NavClusterService is active...")
        if (NavClusterService.instance == null) {
            NavClusterService.startService(this)
        }
        checkActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        if (sbn.packageName in TARGET_PACKAGES) {
            if (NavClusterService.instance == null) {
                NavClusterService.startService(this)
            }
            processNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        if (sbn.packageName in TARGET_PACKAGES) {
            Log.i(TAG, "Nav notification removed from ${sbn.packageName}")
            val activeState = NavClusterService.instance?.currentNavState
            if (activeState?.sourcePackage == sbn.packageName) {
                // Check if another target package is still navigating
                checkActiveNotifications()
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

        // Se title e text sono vuoti, ispezioniamo i RemoteViews personalizzati (cruciale per ABRP com.iternio.abrpapp)
        if (title.isEmpty() && text.isEmpty()) {
            val remoteViews = notification.bigContentView ?: notification.contentView
            val extracted = extractTextsFromRemoteViews(remoteViews)
            Log.d(TAG, "[$pkg] Extracted ${extracted.size} strings from RemoteViews: $extracted")
            if (extracted.isNotEmpty()) {
                val distPatternHelper = Regex("^[~]?[\\s]*[0-9]+(?:[.,][0-9]+)?\\s*(m|km|mi|ft)\\.?$", RegexOption.IGNORE_CASE)
                val distIdx = extracted.indexOfFirst { distPatternHelper.matches(it) }
                if (distIdx >= 0) {
                    title = extracted[distIdx]
                    val other = extracted.filterIndexed { i, _ -> i != distIdx }
                    text = other.firstOrNull { !isGenericStatus(it) } ?: other.firstOrNull() ?: ""
                } else {
                    title = extracted.firstOrNull { !isGenericStatus(it) } ?: extracted[0]
                    text = if (extracted.size > 1) extracted[1] else ""
                }
            }
        }

        val appName = when (pkg) {
            "app.revanced.android.apps.maps" -> "Google Maps"
            "com.google.android.apps.maps" -> "Google Maps"
            "com.iternio.abrpapp" -> "ABRP"
            else -> "Nav"
        }

        if (title.isEmpty() && text.isEmpty()) {
            return false
        }

        Log.d(TAG, "[$appName] Ongoing nav - Title: '$title' | Text: '$text' | SubText: '$subText'")

        // Helper regex per identificare stringhe di distanza pura (es. '22 km', '350 m', '1.5 mi')
        val distPattern = Regex("^[~]?[\\s]*[0-9]+(?:[.,][0-9]+)?\\s*(m|km|mi|ft)\\.?$", RegexOption.IGNORE_CASE)

        var distance = ""
        var instruction = ""

        when {
            distPattern.matches(title) -> {
                // Formato Google Maps ReVanced: Title='22 km', Text='Rimani su A90'
                distance = title
                instruction = text
            }
            distPattern.matches(text) -> {
                // Formato alternativo: Title='Svolta a destra', Text='300 m'
                distance = text
                instruction = title
            }
            title.contains(" - ") -> {
                val parts = title.split(" - ", limit = 2)
                distance = parts[0].trim()
                instruction = if (text.isNotEmpty()) "${parts[1].trim()} - $text" else parts[1].trim()
            }
            else -> {
                distance = title
                instruction = text
            }
        }

        // Parsing avanzato di ETA e tempo/distanza residua
        var eta = ""
        var remaining = ""

        if (subText.isNotEmpty()) {
            remaining = subText
            val arrivalMatch = Regex("(?i)(?:arrivo|eta|arrival)\\s*([0-9]{1,2}[:.][0-9]{2})").find(subText)
            eta = if (arrivalMatch != null) {
                arrivalMatch.groupValues[1]
            } else {
                subText
            }
        } else if (text.contains("•") || text.contains("·") || text.contains("min")) {
            remaining = text
        }

        // Se l'istruzione è un mero stato generico ("Navigazione in corso") e non c'è distanza di manovra,
        // evitiamo di pusharla come falsa svolta TBT
        if (isGenericStatus(instruction) && distance.isEmpty()) {
            Log.d(TAG, "[$appName] Ignored generic status without maneuver distance: '$instruction'")
            return false
        }

        val state = NavState(
            isActive = true,
            sourcePackage = pkg,
            sourceName = appName,
            distance = distance,
            instruction = instruction,
            eta = eta,
            remainingTimeDistance = remaining
        )

        NavClusterService.instance?.onNavStateChanged(state)
        return true
    }

    private fun extractTextsFromRemoteViews(rv: RemoteViews?): List<String> {
        rv ?: return emptyList()
        val texts = mutableListOf<String>()

        // 1. Reflection sicura sui campi mActions del RemoteViews (legge direttamente dal Parcel senza dipendere da risorse esterne)
        try {
            val field = rv.javaClass.getDeclaredField("mActions")
            field.isAccessible = true
            val actions = field.get(rv) as? List<*>
            if (actions != null) {
                for (action in actions) {
                    action ?: continue
                    for (f in action.javaClass.declaredFields) {
                        f.isAccessible = true
                        val value = f.get(action)
                        if (value is CharSequence) {
                            val str = value.toString().trim()
                            if (str.isNotEmpty() && !texts.contains(str) && str.length < 200) {
                                texts.add(str)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "extractTextsFromRemoteViews reflection: ${e.message}")
        }

        // 2. Fallback con inflazione in memoria
        if (texts.isEmpty()) {
            try {
                val view = rv.apply(this, null)
                extractTextsFromView(view, texts)
            } catch (e: Exception) {
                Log.d(TAG, "extractTextsFromRemoteViews apply failed: ${e.message}")
            }
        }

        return texts
    }

    private fun extractTextsFromView(view: View, out: MutableList<String>) {
        if (view is TextView) {
            val t = view.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !out.contains(t) && t.length < 200) {
                out.add(t)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                extractTextsFromView(view.getChildAt(i), out)
            }
        }
    }

    private fun isGenericStatus(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("navigazione in corso") ||
                lower.contains("ricalcolo") ||
                lower.contains("ricerca segnale") ||
                lower.contains("in attesa di segnale") ||
                lower.contains("in attesa del segnale")
    }
}
