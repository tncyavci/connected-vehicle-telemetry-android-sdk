package com.connectedvehicle.telemetry.feature.cockpit

import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitMviControllerTest {

    @Test
    fun `ToggleDoorsLock signs command with Ed25519 and unlocks parked vehicle`() {
        val controller = CockpitMviController()
        assertTrue(controller.uiState.value.telemetry.doorsLocked)

        controller.processIntent(CockpitIntent.ToggleDoorsLock)

        val state = controller.uiState.value
        assertFalse(state.telemetry.doorsLocked)
        assertNull(state.lastSafetyRejectionMessage)
        assertNotNull(state.lastSignedCommand)
        assertEquals(RemoteActuationType.UNLOCK_DOORS, state.lastSignedCommand?.command?.actuationType)
    }

    @Test
    fun `OpenTrunk is rejected by ISO 26262 safety interlock when drive simulation is active`() {
        val controller = CockpitMviController()
        controller.processIntent(CockpitIntent.ToggleDriveSimulation)
        assertTrue(controller.uiState.value.isDriveSimulationActive)
        assertTrue(controller.uiState.value.telemetry.speedKmh > 0.0)

        controller.processIntent(CockpitIntent.OpenTrunk)

        val state = controller.uiState.value
        assertFalse(state.telemetry.trunkOpen)
        assertNotNull(state.lastSafetyRejectionMessage)
        assertTrue(state.commandAuditLog.first().contains("BLOCKED"))
    }

    @Test
    fun `SimulateStepTick with P0A80 fault updates health report and ClearDiagnosticFaults restores 100`() {
        val controller = CockpitMviController()
        assertEquals(100, controller.uiState.value.healthReport.healthScore)

        controller.processIntent(CockpitIntent.SimulateStepTick(injectFaultCode = "P0A80"))
        assertEquals(55, controller.uiState.value.healthReport.healthScore)

        controller.processIntent(CockpitIntent.ClearDiagnosticFaults)
        assertEquals(100, controller.uiState.value.healthReport.healthScore)
    }
}
