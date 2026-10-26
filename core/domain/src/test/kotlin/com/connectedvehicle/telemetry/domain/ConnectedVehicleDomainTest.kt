package com.connectedvehicle.telemetry.domain

import com.connectedvehicle.telemetry.domain.diagnostics.DtcDiagnosticsEngine
import com.connectedvehicle.telemetry.domain.model.ChargingState
import com.connectedvehicle.telemetry.domain.model.DtcSeverity
import com.connectedvehicle.telemetry.domain.model.DtcSubsystem
import com.connectedvehicle.telemetry.domain.model.GearPosition
import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.TirePressureSnapshot
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame
import com.connectedvehicle.telemetry.domain.safety.CommandSafetyPolicyEngine
import com.connectedvehicle.telemetry.domain.safety.CommandSafetyPolicyEngine.RejectionReason
import com.connectedvehicle.telemetry.domain.safety.CommandSafetyPolicyEngine.SafetyVerdict
import com.connectedvehicle.telemetry.domain.simulator.DeterministicCanBusSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedVehicleDomainTest {

    private val diagnosticsEngine = DtcDiagnosticsEngine()
    private val safetyEngine = CommandSafetyPolicyEngine()

    private fun baselineStationaryFrame(
        speedKmh: Double = 0.0,
        gear: GearPosition = GearPosition.PARK,
        soc: Double = 75.0,
        hvTemp: Double = 28.0,
        minTireBar: Double = 2.3,
        dtcs: List<String> = emptyList(),
    ) = VehicleTelemetryFrame(
        vin = "TR-TOFAS-CONNECT-2026-001",
        sequenceNumber = 42L,
        timestampEpochMs = 1_760_000_000_000L,
        speedKmh = speedKmh,
        rpm = if (speedKmh > 0) 2100 else 0,
        gearPosition = gear,
        batterySocPercent = soc,
        estimatedRangeKm = (soc * 4.2).toInt(),
        hvBatteryTempCelsius = hvTemp,
        cabinTempCelsius = 23.0,
        hvacTargetTempCelsius = 21.0,
        hvacActive = false,
        doorsLocked = true,
        trunkOpen = false,
        chargingState = ChargingState.DISCONNECTED,
        tirePressuresBar = TirePressureSnapshot(minTireBar, 2.3, 2.3, 2.3),
        latitude = 41.0082,
        longitude = 28.9784,
        activeDtcCodes = dtcs,
    )

    @Test
    fun `parseDtc accurately classifies Powertrain Chassis Body and Network codes`() {
        val hvFault = diagnosticsEngine.parseDtc("p0a80")
        assertEquals("P0A80", hvFault.code)
        assertEquals(DtcSubsystem.POWERTRAIN, hvFault.subsystem)
        assertFalse(hvFault.isOemSpecific)
        assertEquals(DtcSeverity.CRITICAL_STOP_VEHICLE, hvFault.severity)

        val oemChassis = diagnosticsEngine.parseDtc("C1402")
        assertEquals(DtcSubsystem.CHASSIS, oemChassis.subsystem)
        assertTrue(oemChassis.isOemSpecific)
        assertEquals(DtcSeverity.WARNING, oemChassis.severity)

        val canBusLoss = diagnosticsEngine.parseDtc("U0100")
        assertEquals(DtcSubsystem.NETWORK, canBusLoss.subsystem)
        assertEquals(DtcSeverity.CRITICAL_STOP_VEHICLE, canBusLoss.severity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseDtc rejects malformed DTC strings`() {
        diagnosticsEngine.parseDtc("X99999")
    }

    @Test
    fun `evaluateFrameHealth deducts score on critical DTC thermal spike and low tire pressure`() {
        val healthyReport = diagnosticsEngine.evaluateFrameHealth(baselineStationaryFrame())
        assertEquals(100, healthyReport.healthScore)
        assertEquals(DtcSeverity.INFO, healthyReport.overallSeverity)

        val degradedFrame = baselineStationaryFrame(
            hvTemp = 52.5,
            minTireBar = 1.75,
            dtcs = listOf("P0A80"),
        )
        val degradedReport = diagnosticsEngine.evaluateFrameHealth(degradedFrame)
        assertEquals(10, degradedReport.healthScore) // 100 - 45 (P0A80) - 30 (HV thermal) - 15 (tire)
        assertEquals(DtcSeverity.CRITICAL_STOP_VEHICLE, degradedReport.overallSeverity)
        assertEquals(3, degradedReport.activeAlerts.size)
    }

    @Test
    fun `safetyEngine blocks door unlock and trunk open while vehicle is in motion`() {
        val movingFrame = baselineStationaryFrame(speedKmh = 48.0, gear = GearPosition.DRIVE)
        val unlockCmd = RemoteVehicleCommand(
            commandId = "cmd-001",
            vin = movingFrame.vin,
            actuationType = RemoteActuationType.UNLOCK_DOORS,
            issuedAtEpochMs = 1_760_000_000_000L,
            expiresAtEpochMs = 1_760_000_030_000L,
            nonceHex = "a1b2c3d4e5f60001",
        )

        val verdict = safetyEngine.validate(unlockCmd, movingFrame, nowEpochMs = 1_760_000_005_000L)
        assertTrue(verdict is SafetyVerdict.Rejected)
        assertEquals(RejectionReason.VEHICLE_IN_MOTION, (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `safetyEngine blocks HVAC preconditioning when battery SoC is below 15 percent`() {
        val lowSocFrame = baselineStationaryFrame(soc = 11.5)
        val hvacCmd = RemoteVehicleCommand(
            commandId = "cmd-002",
            vin = lowSocFrame.vin,
            actuationType = RemoteActuationType.START_HVAC_PRECONDITIONING,
            targetTemperatureCelsius = 21.5,
            issuedAtEpochMs = 1_760_000_000_000L,
            expiresAtEpochMs = 1_760_000_030_000L,
            nonceHex = "a1b2c3d4e5f60002",
        )

        val verdict = safetyEngine.validate(hvacCmd, lowSocFrame, nowEpochMs = 1_760_000_005_000L)
        assertTrue(verdict is SafetyVerdict.Rejected)
        assertEquals(RejectionReason.BATTERY_SOC_TOO_LOW, (verdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `safetyEngine approves valid command and blocks subsequent replay with identical nonce`() {
        val parkedFrame = baselineStationaryFrame()
        val unlockCmd = RemoteVehicleCommand(
            commandId = "cmd-003",
            vin = parkedFrame.vin,
            actuationType = RemoteActuationType.UNLOCK_DOORS,
            issuedAtEpochMs = 1_760_000_000_000L,
            expiresAtEpochMs = 1_760_000_030_000L,
            nonceHex = "nonce-unique-998877",
        )

        val firstVerdict = safetyEngine.validate(unlockCmd, parkedFrame, nowEpochMs = 1_760_000_005_000L)
        assertEquals(SafetyVerdict.Approved, firstVerdict)

        val replayVerdict = safetyEngine.validate(unlockCmd, parkedFrame, nowEpochMs = 1_760_000_006_000L)
        assertTrue(replayVerdict is SafetyVerdict.Rejected)
        assertEquals(RejectionReason.REPLAY_NONCE_DETECTED, (replayVerdict as SafetyVerdict.Rejected).reasonCode)
    }

    @Test
    fun `simulator applies remote actuation commands and advances telemetry deterministically`() {
        val simulator = DeterministicCanBusSimulator()
        assertTrue(simulator.currentFrame.value.doorsLocked)

        val unlockCmd = RemoteVehicleCommand(
            commandId = "cmd-sim-1",
            vin = simulator.currentFrame.value.vin,
            actuationType = RemoteActuationType.UNLOCK_DOORS,
            issuedAtEpochMs = 1_760_000_001_000L,
            expiresAtEpochMs = 1_760_000_031_000L,
            nonceHex = "sim-nonce-1",
        )
        val afterUnlock = simulator.applyCommand(unlockCmd)
        assertFalse(afterUnlock.doorsLocked)

        val drivingFrame = simulator.stepTick(driveModeActive = true, injectFaultCode = "P0420")
        assertTrue(drivingFrame.speedKmh > 0.0)
        assertEquals(GearPosition.DRIVE, drivingFrame.gearPosition)
        assertTrue(drivingFrame.activeDtcCodes.contains("P0420"))
    }
}
