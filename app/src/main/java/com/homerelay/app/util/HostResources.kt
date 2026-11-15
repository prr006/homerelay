package com.homerelay.app.util

/**
 * Process-wide registry of the resources the active sharing session holds. The foreground
 * service writes (acquire/release), Diagnostics reads (which locks are held right now), so
 * the Checks screen can prove the host is kept awake without guessing.
 */
object HostResources {
    const val WAKE_LOCK = "partial_wake_lock"
    const val WIFI_LOCK = "wifi_high_perf_lock"

    val guard = ResourceGuard()

    fun heldSummary(): String =
        guard.held().joinToString(", ").ifEmpty { "none" }
}
