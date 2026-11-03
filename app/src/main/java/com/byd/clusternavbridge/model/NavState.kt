package com.byd.clusternavbridge.model

data class NavState(
    val isActive: Boolean = false,
    val sourcePackage: String = "",
    val sourceName: String = "",
    val distance: String = "",
    val instruction: String = "",
    val eta: String = "",
    val remainingTimeDistance: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
