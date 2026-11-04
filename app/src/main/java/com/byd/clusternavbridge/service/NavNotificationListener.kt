package com.byd.clusternavbridge.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
        Log.i(TAG, "NotificationListener connected! Scanning active notifications...")
        checkActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        if (sbn.packageName in TARGET_PACKAGES) {
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
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

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
}
