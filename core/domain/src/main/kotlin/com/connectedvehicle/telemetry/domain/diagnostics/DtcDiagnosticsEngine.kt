package com.connectedvehicle.telemetry.domain.diagnostics

import com.connectedvehicle.telemetry.domain.model.DiagnosticTroubleCode
import com.connectedvehicle.telemetry.domain.model.DtcSeverity
import com.connectedvehicle.telemetry.domain.model.DtcSubsystem
import com.connectedvehicle.telemetry.domain.model.VehicleTelemetryFrame

/**
 * Deterministic ISO 15031-6 / SAE J2012 OBD-II & EV Powertrain Diagnostic Trouble Code (DTC)
 * parser and real-time vehicle health evaluator.
 */
class DtcDiagnosticsEngine {

    private val knownCodes: Map<String, Pair<DtcSeverity, Pair<String, String>>> = mapOf(
        "P0A80" to (DtcSeverity.CRITICAL_STOP_VEHICLE to (
            "Hybrid/EV High-Voltage Battery Pack Deterioration" to
                "Stop vehicle safely and schedule immediate HV battery module inspection."
            )),
        "P0AA6" to (DtcSeverity.CRITICAL_STOP_VEHICLE to (
            "High-Voltage Battery System Isolation Fault" to
                "Do not initiate DC fast charging; contact authorized service immediately."
            )),
        "P0300" to (DtcSeverity.CRITICAL_STOP_VEHICLE to (
            "Random/Multiple Cylinder Misfire Detected" to
                "Reduce engine load immediately to prevent catalytic converter thermal damage."
            )),
        "P0420" to (DtcSeverity.WARNING to (
            "Catalyst System Efficiency Below Threshold (Bank 1)" to
                "Schedule emissions & oxygen sensor diagnostics at next service window."
            )),
        "C0035" to (DtcSeverity.WARNING to (
            "Left Front Wheel Speed Sensor Supply Circuit" to
                "ABS/ESC stability intervention may be degraded; inspect wheel hub harness."
            )),
        "B1200" to (DtcSeverity.INFO to (
            "Climate Control Pushbutton / HVAC Flap Actuator Circuit" to
                "Cabin pre-conditioning efficiency may be reduced."
            )),
        "U0100" to (DtcSeverity.CRITICAL_STOP_VEHICLE to (
            "Lost Communication With ECM/PCM 'A' on CAN-Bus" to
                "Check gateway ECU termination resistance and main powertrain harness."
            )),
    )

    /**
     * Parses a 5-character SAE J2012 DTC string (e.g., `P0A80`, `U0100`, `C1234`).
     * Throws [IllegalArgumentException] if the code violates the standard 5-character format.
     */
    fun parseDtc(rawCode: String): DiagnosticTroubleCode {
        val normalized = rawCode.trim().uppercase()
        require(DTC_REGEX.matches(normalized)) {
            "Invalid SAE J2012 DTC format: '$rawCode'. Expected [P|C|B|U][0-3][0-9A-F]{3}."
        }

        val subsystem = when (normalized[0]) {
            'P' -> DtcSubsystem.POWERTRAIN
            'C' -> DtcSubsystem.CHASSIS
            'B' -> DtcSubsystem.BODY
            'U' -> DtcSubsystem.NETWORK
            else -> error("Unreachable prefix: ${normalized[0]}")
        }

        val isOemSpecific = normalized[1] == '1' || normalized[1] == '3'
        val known = knownCodes[normalized]

        val severity = known?.first ?: when {
            subsystem == DtcSubsystem.NETWORK -> DtcSeverity.CRITICAL_STOP_VEHICLE
            subsystem == DtcSubsystem.POWERTRAIN && !isOemSpecific -> DtcSeverity.WARNING
            subsystem == DtcSubsystem.CHASSIS -> DtcSeverity.WARNING
            else -> DtcSeverity.INFO
        }

        val summary = known?.second?.first
            ?: "${if (isOemSpecific) "OEM-Specific" else "Standard"} ${subsystem.description} Fault ($normalized)"

        val action = known?.second?.second
            ?: "Book authorized service telemetry readout for code $normalized."

        return DiagnosticTroubleCode(
            code = normalized,
            subsystem = subsystem,
            isOemSpecific = isOemSpecific,
            severity = severity,
            summary = summary,
            recommendedAction = action,
        )
    }

    /**
     * Evaluates a live [VehicleTelemetryFrame] for active DTCs, thermal anomalies,
     * tire pressure drops, and low-SOC conditions, producing a 0..100 vehicle health score.
     */
    fun evaluateFrameHealth(frame: VehicleTelemetryFrame): VehicleHealthReport {
        val parsedDtcs = frame.activeDtcCodes.mapNotNull { code ->
            runCatching { parseDtc(code) }.getOrNull()
        }

        val alerts = mutableListOf<String>()
        var penalty = 0

        for (dtc in parsedDtcs) {
            penalty += when (dtc.severity) {
                DtcSeverity.CRITICAL_STOP_VEHICLE -> 45
                DtcSeverity.WARNING -> 20
                DtcSeverity.INFO -> 5
            }
            alerts += "[${dtc.code}] ${dtc.summary}"
        }

        if (frame.hvBatteryTempCelsius >= 50.0) {
            penalty += 30
            alerts += "High-voltage battery thermal alert: ${frame.hvBatteryTempCelsius}°C (Threshold: 50.0°C)"
        }

        if (frame.tirePressuresBar.minimumBar() < 1.9) {
            penalty += 15
            alerts += "Low tire pressure detected: min ${frame.tirePressuresBar.minimumBar()} bar (Recommended: 2.3 bar)"
        }

        if (frame.batterySocPercent < 10.0) {
            penalty += 10
            alerts += "Low state of charge (${frame.batterySocPercent}%) — navigate to nearest charging station."
        }

        val healthScore = (100 - penalty).coerceIn(0, 100)
        val overallStatus = when {
            parsedDtcs.any { it.severity == DtcSeverity.CRITICAL_STOP_VEHICLE } || frame.hvBatteryTempCelsius >= 55.0 ->
                DtcSeverity.CRITICAL_STOP_VEHICLE
            healthScore < 80 -> DtcSeverity.WARNING
            else -> DtcSeverity.INFO
        }

        return VehicleHealthReport(
            vin = frame.vin,
            healthScore = healthScore,
            overallSeverity = overallStatus,
            parsedDtcs = parsedDtcs,
            activeAlerts = alerts,
        )
    }

    companion object {
        private val DTC_REGEX = Regex("^[PCBU][0-3][0-9A-F]{3}$")
    }
}

data class VehicleHealthReport(
    val vin: String,
    val healthScore: Int,
    val overallSeverity: DtcSeverity,
    val parsedDtcs: List<DiagnosticTroubleCode>,
    val activeAlerts: List<String>,
)
