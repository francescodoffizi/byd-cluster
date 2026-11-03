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
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        val appName = when (pkg) {
            "app.revanced.android.apps.maps" -> "Google Maps"
            "com.google.android.apps.maps" -> "Google Maps"
            "com.iternio.abrpapp" -> "ABRP"
            else -> "Nav"
        }

        Log.d(TAG, "[$appName] Ongoing nav - Title: '$title' | Text: '$text' | SubText: '$subText'")

        // Parse turn, distance, instruction, ETA
        var distance = ""
        var instruction = text
        var eta = ""
        var remaining = ""

        if (title.isNotEmpty()) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2) {
                distance = parts[0].trim()
                instruction = parts[1].trim()
            } else {
                distance = title
            }
        }

        if (subText.isNotEmpty()) {
            eta = subText
        } else if (text.contains("•") || text.contains("min")) {
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
