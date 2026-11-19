package com.crypticsystems.warpair

/**
 * Physical transports that may carry the same logical WarPair transfer.
 * WIFI_LAN includes a shared router network and the current SoftAP TCP path.
 */
enum class TransferTransport {
    WIFI_LAN,
    WIFI_DIRECT,
    BLUETOOTH,
}

data class TransportAvailability(
    val wifiLan: Boolean = false,
    val wifiDirect: Boolean = false,
    val bluetooth: Boolean = false,
) {
    fun supports(transport: TransferTransport): Boolean = when (transport) {
        TransferTransport.WIFI_LAN -> wifiLan
        TransferTransport.WIFI_DIRECT -> wifiDirect
        TransferTransport.BLUETOOTH -> bluetooth
    }
}

enum class TransportPreference { AUTOMATIC, WIFI_FIRST, BLUETOOTH_FIRST }

data class TransferTransportPlan(
    val candidates: List<TransferTransport>,
    val suggestedForSmallFile: TransferTransport?,
) {
    val primary: TransferTransport?
        get() = candidates.firstOrNull()

    fun fallbackAfter(failed: TransferTransport): TransferTransport? {
        val index = candidates.indexOf(failed)
        if (index < 0) return primary
        return candidates.getOrNull(index + 1)
    }
}

/**
 * Chooses only transports that the platform has actually reported as available.
 * It does not claim that a transport is connected: adapters must still perform
 * a real reachability/handshake check before starting or resuming a transfer.
 */
object TransferTransportPolicy {
    const val SMALL_FILE_LIMIT_BYTES = 10L * 1024 * 1024

    fun plan(
        fileSizeBytes: Long,
        availability: TransportAvailability,
        preference: TransportPreference = TransportPreference.AUTOMATIC,
    ): TransferTransportPlan {
        require(fileSizeBytes >= 0) { "file size cannot be negative" }

        val wifi = listOf(TransferTransport.WIFI_DIRECT, TransferTransport.WIFI_LAN)
            .filter(availability::supports)
        val bluetooth = listOf(TransferTransport.BLUETOOTH)
            .filter(availability::supports)

        val ordered = when (preference) {
            TransportPreference.WIFI_FIRST -> wifi + bluetooth
            TransportPreference.BLUETOOTH_FIRST -> bluetooth + wifi
            TransportPreference.AUTOMATIC -> wifi + bluetooth
        }.distinct()

        val smallFileSuggestion = if (
            fileSizeBytes <= SMALL_FILE_LIMIT_BYTES &&
            availability.bluetooth &&
            preference == TransportPreference.AUTOMATIC
        ) TransferTransport.BLUETOOTH else null

        return TransferTransportPlan(
            candidates = ordered,
            suggestedForSmallFile = smallFileSuggestion,
        )
    }
}

/**
 * Tracks attempted routes for one logical transfer. The transfer identity,
 * selected files and receiver checkpoint stay outside this route cursor, so
 * changing transports cannot implicitly reset transfer progress.
 */
class TransportFailoverSequence(candidates: List<TransferTransport>) {
    private val ordered = candidates.distinct()
    private val attempted = linkedSetOf<TransferTransport>()

    fun current(): TransferTransport? = ordered.firstOrNull { it !in attempted }

    fun markFailed(transport: TransferTransport): TransferTransport? {
        require(transport in ordered) { "transport was not part of this plan" }
        attempted += transport
        return current()
    }

    fun attempted(): Set<TransferTransport> = attempted.toSet()
}
