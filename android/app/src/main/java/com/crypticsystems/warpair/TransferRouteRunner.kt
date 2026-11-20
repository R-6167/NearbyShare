package com.crypticsystems.warpair

import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Executes real connection adapters in the planned order while keeping the
 * caller's OutgoingFile instances (and therefore batch/file IDs) unchanged.
 *
 * Receiver-side manifests and partial files remain authoritative: on a new
 * authenticated connection the receiver answers each offer with either
 * already_complete or its durable resume offset.
 */
class TransferRouteRunner(candidates: List<TransferTransport>) {
    private val sequence = TransportFailoverSequence(candidates)
    private val failures = mutableListOf<String>()

    init {
        require(candidates.isNotEmpty()) { "at least one transport candidate is required" }
    }

    fun execute(
        attempts: Map<TransferTransport, () -> String>,
        control: LanLink.TransferControl,
        onRouteChanged: (failed: TransferTransport, next: TransferTransport?, reason: String) -> Unit = { _, _, _ -> },
    ): String {
        var route = sequence.current()
        var lastFailure: Throwable? = null
        while (route != null) {
            control.check()
            try {
                val attempt = attempts[route]
                    ?: throw IOException("No connection adapter is available for $route")
                val result = attempt()
                if (!result.startsWith("BATCH OK")) {
                    throw IOException("$route did not confirm every file: $result")
                }
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                val reason = failure.message ?: failure.javaClass.simpleName
                failures += "$route: $reason"
                val failed = route
                route = sequence.markFailed(failed)
                onRouteChanged(failed, route, reason)
            }
        }
        throw IOException(
            "All available transfer routes failed: ${failures.joinToString(" | ")}",
            lastFailure,
        )
    }
}
