package com.homerelay.app.state

import com.homerelay.app.proxy.ProxyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of a sharing session, as observed by the UI. */
enum class SharingPhase {
    Idle,
    /** Asking Android for the P2P group; proxy not started yet. */
    Starting,
    /** Group formed and proxy bound and listening. Clients may or may not be connected (see clientsConnected). */
    Sharing,
    /** Group and proxy are up, but the pinned upstream home Wi-Fi is lost or unvalidated. Requests will fail. */
    UpstreamUnavailable,
    Stopping,
    Failed,
}

/** Snapshot of everything the UI renders. Immutable; replace copies wholesale. */
data class SharingUiState(
    val phase: SharingPhase = SharingPhase.Idle,
    val errorMessage: String? = null,
    /** Wi-Fi Direct group network name (SSID), e.g. "DIRECT-K7q2R9". */
    val p2pSsid: String? = null,
    /** Wi-Fi Direct group passphrase for legacy clients. */
    val p2pPassphrase: String? = null,
    /** Interface the group was created on, as reported by the framework (e.g. "p2p0"). */
    val groupInterfaceName: String? = null,
    /** Group frequency in MHz, as reported by the framework. */
    val groupFrequencyMhz: Int? = null,
    val groupSecurityType: String? = null,
    /** True when Android reports this device as the group owner (HomeRelay creates it as GO). */
    val groupIsGroupOwner: Boolean? = null,
    /** True when the upstream home Wi-Fi was still alive immediately after the group was created. Null if untested. */
    val upstreamSurvivedGroupCreation: Boolean? = null,
    /** v1.2.4: which group creation mode this session used (e.g. "A · Custom SSID, 2.4 GHz (default)"); null when idle. */
    val groupCreationMode: String? = null,
    /** Clients currently in the group (from requestGroupInfo). */
    val clientsConnected: Int = 0,
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
    /** True only while the proxy is definitely live and the group definitely exists. */
    val sharing: Boolean
        get() = phase == SharingPhase.Sharing || phase == SharingPhase.UpstreamUnavailable
    val busy: Boolean
        get() = phase == SharingPhase.Starting || phase == SharingPhase.Stopping
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
        _state.value = SharingUiState(proxyPort = port) // groupCreationMode resets to null with the rest
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
            activeConnections = snapshot.activeConnections.toInt(),
            proxyPort = port,
        )
    }
}
