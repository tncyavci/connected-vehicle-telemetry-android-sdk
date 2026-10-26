package com.connectedvehicle.telemetry.feature.cockpit

import androidx.compose.runtime.Immutable
import com.connectedvehicle.telemetry.crypto.Ed25519VehicleCommandSigner
import com.connectedvehicle.telemetry.domain.diagnostics.DtcDiagnosticsEngine
import com.connectedvehicle.telemetry.domain.diagnostics.VehicleHealthReport
import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame
import com.connectedvehicle.telemetry.domain.safety.CommandSafetyPolicyEngine
import com.connectedvehicle.telemetry.domain.safety.CommandSafetyPolicyEngine.SafetyVerdict
import com.connectedvehicle.telemetry.domain.simulator.DeterministicCanBusSimulator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@Immutable
data class CockpitUiState(
    val telemetry: VehicleTelemetryFrame,
    val healthReport: VehicleHealthReport,
    val isDriveSimulationActive: Boolean = false,
    val lastSignedCommand: SignedVehicleCommandEnvelope? = null,
    val lastSafetyRejectionMessage: String? = null,
    val commandAuditLog: List<String> = emptyList(),
)

sealed interface CockpitIntent {
    data object ToggleDoorsLock : CockpitIntent
    data class SetHvacPreconditioning(val active: Boolean, val targetTempCelsius: Double = 21.5) : CockpitIntent
    data object OpenTrunk : CockpitIntent
    data object ToggleDriveSimulation : CockpitIntent
    data class SimulateStepTick(val injectFaultCode: String? = null) : CockpitIntent
    data object ClearDiagnosticFaults : CockpitIntent
}

/**
 * Unidirectional MVI State Holder for the Connected Vehicle Digital Cockpit.
 *
 * Orchestrates:
 * 1. Real-time CAN-Bus / OBD-II telemetry ingestion.
 * 2. ISO 26262 safety interlock validation ([CommandSafetyPolicyEngine]).
 * 3. On-device Ed25519 cryptographic signing ([Ed25519VehicleCommandSigner]).
 * 4. SAE J2012 Diagnostic Trouble Code (DTC) health scoring ([DtcDiagnosticsEngine]).
 */
class CockpitMviController(
    private val simulator: DeterministicCanBusSimulator = DeterministicCanBusSimulator(),
    private val diagnosticsEngine: DtcDiagnosticsEngine = DtcDiagnosticsEngine(),
    private val safetyEngine: CommandSafetyPolicyEngine = CommandSafetyPolicyEngine(),
    private val commandSigner: Ed25519VehicleCommandSigner = Ed25519VehicleCommandSigner(),
) {
    private val _uiState = MutableStateFlow(
        CockpitUiState(
            telemetry = simulator.currentFrame.value,
            healthReport = diagnosticsEngine.evaluateFrameHealth(simulator.currentFrame.value),
        )
    )
    val uiState: StateFlow<CockpitUiState> = _uiState.asStateFlow()

    fun processIntent(intent: CockpitIntent) {
        when (intent) {
            is CockpitIntent.ToggleDoorsLock -> {
                val current = _uiState.value.telemetry
                val actuation = if (current.doorsLocked) {
                    RemoteActuationType.UNLOCK_DOORS
                } else {
                    RemoteActuationType.LOCK_DOORS
                }
                executeRemoteActuation(actuation, null)
            }

            is CockpitIntent.SetHvacPreconditioning -> {
                val actuation = if (intent.active) {
                    RemoteActuationType.START_HVAC_PRECONDITIONING
                } else {
                    RemoteActuationType.STOP_HVAC_PRECONDITIONING
                }
                executeRemoteActuation(actuation, intent.targetTempCelsius)
            }

            is CockpitIntent.OpenTrunk -> {
                executeRemoteActuation(RemoteActuationType.OPEN_TRUNK, null)
            }

            is CockpitIntent.ToggleDriveSimulation -> {
                val nextDriveState = !_uiState.value.isDriveSimulationActive
                val nextFrame = simulator.stepTick(driveModeActive = nextDriveState)
                _uiState.value = _uiState.value.copy(
                    telemetry = nextFrame,
                    healthReport = diagnosticsEngine.evaluateFrameHealth(nextFrame),
                    isDriveSimulationActive = nextDriveState,
                    lastSafetyRejectionMessage = null,
                )
            }

            is CockpitIntent.SimulateStepTick -> {
                val nextFrame = simulator.stepTick(
                    driveModeActive = _uiState.value.isDriveSimulationActive,
                    injectFaultCode = intent.injectFaultCode,
                )
                _uiState.value = _uiState.value.copy(
                    telemetry = nextFrame,
                    healthReport = diagnosticsEngine.evaluateFrameHealth(nextFrame),
                )
            }

            is CockpitIntent.ClearDiagnosticFaults -> {
                val clearedFrame = simulator.clearFaultCodes()
                _uiState.value = _uiState.value.copy(
                    telemetry = clearedFrame,
                    healthReport = diagnosticsEngine.evaluateFrameHealth(clearedFrame),
                )
            }
        }
    }

    private fun executeRemoteActuation(
        actuationType: RemoteActuationType,
        targetTempCelsius: Double?,
    ) {
        val currentTelemetry = _uiState.value.telemetry
        val nowMs = currentTelemetry.timestampEpochMs + 500L
        val command = RemoteVehicleCommand(
            commandId = "cmd-${UUID.randomUUID().toString().take(8)}",
            vin = currentTelemetry.vin,
            actuationType = actuationType,
            targetTemperatureCelsius = targetTempCelsius,
            issuedAtEpochMs = nowMs,
            expiresAtEpochMs = nowMs + 30_000L,
            nonceHex = UUID.randomUUID().toString().replace("-", "").take(16),
        )

        when (val verdict = safetyEngine.validate(command, currentTelemetry, nowEpochMs = nowMs)) {
            is SafetyVerdict.Approved -> {
                val signedEnvelope = commandSigner.sign(command)
                val updatedFrame = simulator.applyCommand(command)
                val logEntry = "✓ SIGNED [${signedEnvelope.command.actuationType}] sig=${signedEnvelope.signatureBase64.take(12)}..."
                _uiState.value = _uiState.value.copy(
                    telemetry = updatedFrame,
                    healthReport = diagnosticsEngine.evaluateFrameHealth(updatedFrame),
                    lastSignedCommand = signedEnvelope,
                    lastSafetyRejectionMessage = null,
                    commandAuditLog = (listOf(logEntry) + _uiState.value.commandAuditLog).take(8),
                )
            }

            is SafetyVerdict.Rejected -> {
                val logEntry = "✗ BLOCKED [${command.actuationType}] (${verdict.reasonCode}): ${verdict.message}"
                _uiState.value = _uiState.value.copy(
                    lastSafetyRejectionMessage = verdict.message,
                    commandAuditLog = (listOf(logEntry) + _uiState.value.commandAuditLog).take(8),
                )
            }
        }
    }
}
