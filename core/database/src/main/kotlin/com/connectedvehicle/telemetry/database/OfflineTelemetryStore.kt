package com.connectedvehicle.telemetry.database

import app.cash.sqldelight.db.SqlDriver
import com.connectedvehicle.telemetry.database.sql.VehicleTelemetryDatabase
import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline-first SQLDelight persistence store for vehicle telemetry frames and
 * cryptographically signed actuation commands queued during tunnel/underground dead-zones.
 */
class OfflineTelemetryStore(
    driver: SqlDriver,
) {
    private val database = VehicleTelemetryDatabase(driver)
    private val queries = database.vehicleTelemetryQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun saveTelemetryFrame(frame: VehicleTelemetryFrame) {
        queries.upsertTelemetryFrame(
            vin = frame.vin,
            sequence_number = frame.sequenceNumber,
            timestamp_epoch_ms = frame.timestampEpochMs,
            speed_kmh = frame.speedKmh,
            battery_soc_percent = frame.batterySocPercent,
            estimated_range_km = frame.estimatedRangeKm.toLong(),
            doors_locked = if (frame.doorsLocked) 1L else 0L,
            hvac_active = if (frame.hvacActive) 1L else 0L,
            frame_json = json.encodeToString(frame),
        )
    }

    fun getLatestFrame(vin: String): VehicleTelemetryFrame? {
        val row = queries.selectLatestFrameForVin(vin).executeAsOneOrNull() ?: return null
        return json.decodeFromString<VehicleTelemetryFrame>(row.frame_json)
    }

    fun enqueueSignedCommand(envelope: SignedVehicleCommandEnvelope) {
        queries.enqueueCommand(
            command_id = envelope.command.commandId,
            vin = envelope.command.vin,
            actuation_type = envelope.command.actuationType.name,
            issued_at_epoch_ms = envelope.command.issuedAtEpochMs,
            expires_at_epoch_ms = envelope.command.expiresAtEpochMs,
            envelope_json = json.encodeToString(envelope),
            sync_status = "PENDING",
        )
    }

    fun getPendingCommands(): List<SignedVehicleCommandEnvelope> {
        return queries.selectPendingCommands().executeAsList().map { row ->
            json.decodeFromString<SignedVehicleCommandEnvelope>(row.envelope_json)
        }
    }

    fun markCommandDispatched(commandId: String) {
        queries.markCommandDispatched(commandId)
    }
}
