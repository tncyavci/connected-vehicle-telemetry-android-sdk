package com.connectedvehicle.telemetry.feature.cockpit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.connectedvehicle.telemetry.feature.cockpit.CockpitIntent
import com.connectedvehicle.telemetry.feature.cockpit.CockpitMviController

@Composable
fun ConnectedVehicleCockpitScreen(
    controller: CockpitMviController,
    modifier: Modifier = Modifier,
) {
    val state by controller.uiState.collectAsState()
    val frame = state.telemetry
    val health = state.healthReport

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0B0F19),
        contentColor = Color(0xFFF3F4F6),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "CONNECTED VEHICLE TELEMETRY COCKPIT",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
            )
            Text(
                text = "VIN: ${frame.vin} • Seq #${frame.sequenceNumber} • Health: ${health.healthScore}/100",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9CA3AF),
            )

            // Primary Instrument Cluster
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("SPEED", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                        Text(
                            text = "${frame.speedKmh} km/h",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                        Text("Gear: ${frame.gearPosition} (${frame.rpm} RPM)", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("HV BATTERY SoC", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                        Text(
                            text = "${frame.batterySocPercent}% (${frame.estimatedRangeKm} km)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399),
                        )
                        Text(
                            text = "Cabin: ${frame.cabinTempCelsius}°C | HV: ${frame.hvBatteryTempCelsius}°C",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Safety Interlock Banner
            state.lastSafetyRejectionMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "ISO 26262 Safety Interlock: $msg",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFECACA),
                    )
                }
            }

            // Remote Actuation Controls (Ed25519 Signed)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "REMOTE ACTUATION (Ed25519 Signed)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF38BDF8),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { controller.processIntent(CockpitIntent.ToggleDoorsLock) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (frame.doorsLocked) "Unlock Doors" else "Lock Doors")
                        }
                        Button(
                            onClick = {
                                controller.processIntent(
                                    CockpitIntent.SetHvacPreconditioning(active = !frame.hvacActive, targetTempCelsius = 21.5)
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (frame.hvacActive) "Stop HVAC" else "Start HVAC 21.5°C")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { controller.processIntent(CockpitIntent.OpenTrunk) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Open Trunk")
                        }
                        OutlinedButton(
                            onClick = { controller.processIntent(CockpitIntent.ToggleDriveSimulation) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.isDriveSimulationActive) "Park Vehicle" else "Simulate Drive")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { controller.processIntent(CockpitIntent.SimulateStepTick(injectFaultCode = "P0A80")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Inject P0A80 Fault")
                        }
                        OutlinedButton(
                            onClick = { controller.processIntent(CockpitIntent.ClearDiagnosticFaults) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear DTCs")
                        }
                    }
                }
            }

            // Active Diagnostics & Audit Trail
            if (health.activeAlerts.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ACTIVE OBD-II / CAN-BUS ALERTS", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFBBF24))
                        health.activeAlerts.forEach { alert ->
                            Text("• $alert", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFDE68A))
                        }
                    }
                }
            }

            if (state.commandAuditLog.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text("CRYPTOGRAPHIC AUDIT LOG", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
                    Spacer(modifier = Modifier.height(4.dp))
                    state.commandAuditLog.forEach { entry ->
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFD1D5DB),
                        )
                    }
                }
            }
        }
    }
}
