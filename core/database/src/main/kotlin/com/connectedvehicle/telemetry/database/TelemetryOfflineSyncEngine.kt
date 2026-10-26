package com.connectedvehicle.telemetry.database

import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope

/**
 * Functional interface decoupling the offline SQLDelight command queue from the transport implementation.
 */
fun interface SignedCommandDispatcher {
    suspend fun dispatch(envelope: SignedVehicleCommandEnvelope): Boolean
}

data class SyncFlushReport(
    val totalPending: Int,
    val dispatchedCount: Int,
    val expiredDroppedCount: Int,
    val remainingInQueue: Int,
)

/**
 * Coordinates store-and-forward execution of Ed25519-signed vehicle commands queued while
 * the vehicle or mobile client was inside a tunnel / cellular dead-zone.
 *
 * Automatically drops commands whose TTL (`expiresAtEpochMs`) has already elapsed before
 * network restoration so stale actuations are never replayed to the vehicle TCU.
 */
class TelemetryOfflineSyncEngine(
    private val store: OfflineTelemetryStore,
    private val dispatcher: SignedCommandDispatcher,
) {
    suspend fun flushPendingCommands(nowEpochMs: Long): SyncFlushReport {
        val pending = store.getPendingCommands()
        var dispatched = 0
        var expired = 0

        for (envelope in pending) {
            if (nowEpochMs > envelope.command.expiresAtEpochMs) {
                // Mark expired commands as dispatched/pruned so stale actuations never fire late
                store.markCommandDispatched(envelope.command.commandId)
                expired++
                continue
            }

            val delivered = dispatcher.dispatch(envelope)
            if (delivered) {
                store.markCommandDispatched(envelope.command.commandId)
                dispatched++
            } else {
                // Stop flushing on first transport failure to preserve strict FIFO command ordering
                break
            }
        }

        val remaining = store.getPendingCommands().size
        return SyncFlushReport(
            totalPending = pending.size,
            dispatchedCount = dispatched,
            expiredDroppedCount = expired,
            remainingInQueue = remaining,
        )
    }
}
