package com.homerelay.app.state

import com.homerelay.app.proxy.ProxyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of a sharing session, as observed by the UI. */
enum class SharingPhase {
    Idle,
    StartingHotspot,
    StartingProxy,
    Sharing,
    Stopping,
    Failed,
}

/** Snapshot of everything the UI renders. Immutable; replace copies wholesale. */
data class SharingUiState(
    val phase: SharingPhase = SharingPhase.Idle,
    val errorMessage: String? = null,
    val hotspotSsid: String? = null,
    val hotspotPassphrase: String? = null,
    val bindAddress: String? = null,
    val proxyPort: Int = 8080,
    val pacUrl: String? = null,
    val startedAtElapsedRealtimeMs: Long = 0L,
    val startedAtWallClockMs: Long = 0L,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val totalRequests: Long = 0L,
    val successfulRequests: Long = 0L,
    val failedRequests: Long = 0L,
    val connectTunnels: Long = 0L,
    val seenDevices: Int = 0,
    val activeConnections: Int = 0,
) {
    val sharing: Boolean
        get() = phase == SharingPhase.Sharing || phase == SharingPhase.StartingProxy
    val busy: Boolean
        get() = phase == SharingPhase.StartingHotspot || phase == SharingPhase.StartingProxy || phase == SharingPhase.Stopping
}

/**
 * Process-wide holder for the single sharing session's UI state. The foreground service
 * writes; Compose reads. Deliberately simple (no DI framework) so the data path is obvious.
 */
object AppState {
    private val _state = MutableStateFlow(SharingUiState())
    val state: StateFlow<SharingUiState> = _state.asStateFlow()

    fun update(transform: (SharingUiState) -> SharingUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        val port = _state.value.proxyPort
        _state.value = SharingUiState(proxyPort = port)
    }

    fun applySnapshot(snapshot: ProxyStats.Snapshot, port: Int) {
        _state.value = _state.value.copy(
            bytesUp = snapshot.bytesUp,
            bytesDown = snapshot.bytesDown,
            totalRequests = snapshot.totalRequests,
            successfulRequests = snapshot.successfulRequests,
            failedRequests = snapshot.failedRequests,
            connectTunnels = snapshot.connectTunnels,
            seenDevices = snapshot.seenDevices,
            activeConnections = snapshot.activeConnections,
            proxyPort = port,
        )
    }
}
