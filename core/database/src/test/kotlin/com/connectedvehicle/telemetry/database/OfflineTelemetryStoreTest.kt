package com.connectedvehicle.telemetry.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.connectedvehicle.telemetry.database.sql.VehicleTelemetryDatabase
import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope
import com.connectedvehicle.telemetry.domain.simulator.DeterministicCanBusSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTelemetryStoreTest {

    private fun createInMemoryStore(): OfflineTelemetryStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VehicleTelemetryDatabase.Schema.create(driver)
        return OfflineTelemetryStore(driver)
    }

    @Test
    fun `saveTelemetryFrame and getLatestFrame persist and retrieve latest sequence`() {
        val store = createInMemoryStore()
        val simulator = DeterministicCanBusSimulator()

        val firstFrame = simulator.currentFrame.value
        val secondFrame = simulator.stepTick(driveModeActive = true)

        store.saveTelemetryFrame(firstFrame)
        store.saveTelemetryFrame(secondFrame)

        val latest = store.getLatestFrame(firstFrame.vin)
        assertNotNull(latest)
        assertEquals(secondFrame.sequenceNumber, latest?.sequenceNumber)
        assertTrue((latest?.speedKmh ?: 0.0) > 0.0)
    }

    @Test
    fun `enqueueSignedCommand and markCommandDispatched transition offline command queue state`() {
        val store = createInMemoryStore()
        val envelope = SignedVehicleCommandEnvelope(
            command = RemoteVehicleCommand(
                commandId = "cmd-db-01",
                vin = "TR-TOFAS-CONNECT-2026-001",
                actuationType = RemoteActuationType.LOCK_DOORS,
                issuedAtEpochMs = 1_760_000_000_000L,
                expiresAtEpochMs = 1_760_000_030_000L,
                nonceHex = "db-nonce-01",
            ),
            signatureBase64 = "c2lnbmF0dXJl",
            publicKeyId = "key-1",
        )

        store.enqueueSignedCommand(envelope)
        val pendingBefore = store.getPendingCommands()
        assertEquals(1, pendingBefore.size)
        assertEquals("cmd-db-01", pendingBefore.first().command.commandId)

        store.markCommandDispatched("cmd-db-01")
        val pendingAfter = store.getPendingCommands()
        assertTrue(pendingAfter.isEmpty())
    }
}
