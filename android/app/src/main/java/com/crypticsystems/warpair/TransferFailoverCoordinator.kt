package com.crypticsystems.warpair

import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * One attempt to carry the same logical transfer over a physical transport.
 *
 * Implementations must report only receiver-confirmed durable checkpoints
 * through [onStateChanged]. They must not replace transfer identity or file
 * descriptors when a route changes.
 */
fun interface TransportAttempt {
    fun run(
        state: TransferRecoveryState,
        onStateChanged: (TransferRecoveryState) -> Unit,
    ): Boolean
}

data class TransportAttemptFailure(
    val transport: TransferTransport,
    val message: String,
)

class TransportFailoverException(
    val failures: List<TransportAttemptFailure>,
    cause: Throwable?,
) : IOException(
    buildString {
        append("All available transfer routes failed")
        if (failures.isNotEmpty()) {
            append(": ")
            append(failures.joinToString { "${it.transport} (${it.message})" })
        }
    },
    cause,
)

/**
 * Runs transport attempts in policy order while retaining one immutable
 * recovery snapshot. A failed route never resets completed files or durable
 * offsets; the next route receives the latest validated snapshot.
 *
 * This is deliberately a transport-independent coordinator. Android-specific
 * connection establishment and protocol callbacks are supplied by adapters.
 */
class TransferFailoverCoordinator(
    initialState: TransferRecoveryState,
    candidates: List<TransferTransport>,
) {
    private val sequence = TransportFailoverSequence(candidates)
    private val orderedCandidates = candidates.distinct()
    private var currentState = initialState
    private val failures = mutableListOf<TransportAttemptFailure>()

    init {
        require(orderedCandidates.isNotEmpty()) { "at least one transport candidate is required" }
    }

    fun snapshot(): TransferRecoveryState = currentState

    fun failures(): List<TransportAttemptFailure> = failures.toList()

    fun execute(
        attempts: Map<TransferTransport, TransportAttempt>,
        onRouteChanged: (failed: TransferTransport, next: TransferTransport?) -> Unit = { _, _ -> },
    ): TransferRecoveryState {
        var route = sequence.current()
        var lastFailure: Throwable? = null

        while (route != null) {
            try {
                val attempt = attempts[route]
                    ?: throw IOException("No connection adapter is registered for $route")
                val completed = attempt.run(currentState) { candidate ->
                    validateProgress(currentState, candidate)
                    currentState = candidate
                }
                if (!completed) throw IOException("$route ended without completing the logical transfer")
                check(currentState.remainingFiles().isEmpty()) {
                    "$route reported success while files remain incomplete"
                }
                return currentState
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
                failures += TransportAttemptFailure(route, failure.message ?: failure.javaClass.simpleName)
                val failed = route
                route = sequence.markFailed(failed)
                onRouteChanged(failed, route)
            }
        }

        throw TransportFailoverException(failures(), lastFailure)
    }

    private fun validateProgress(
        previous: TransferRecoveryState,
        next: TransferRecoveryState,
    ) {
        require(next.transferId == previous.transferId) {
            "transport retry cannot change the logical transfer id"
        }
        require(next.files == previous.files) {
            "transport retry cannot change the logical file list"
        }
        require(next.completedFileIds.containsAll(previous.completedFileIds)) {
            "transport retry cannot undo completed files"
        }
        previous.checkpoints.forEach { (fileId, oldCheckpoint) ->
            val newCheckpoint = next.checkpoints[fileId]
            if (fileId !in next.completedFileIds) {
                require(newCheckpoint != null) {
                    "transport retry cannot discard a durable checkpoint"
                }
                require(newCheckpoint.sizeBytes == oldCheckpoint.sizeBytes)
                require(newCheckpoint.chunkSizeBytes == oldCheckpoint.chunkSizeBytes)
                require(newCheckpoint.bytesDurable >= oldCheckpoint.bytesDurable) {
                    "transport retry cannot move a durable offset backwards"
                }
            }
        }
    }
}
