package com.connectedvehicle.telemetry.feature.cockpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.connectedvehicle.telemetry.domain.model.TirePressureSnapshot

/**
 * Custom Canvas-drawn 240-degree automotive radial gauge for Speed (km/h) and HV Battery SoC (%).
 * Performs all arc geometry inside the DrawScope lambda to avoid unnecessary recomposition.
 */
@Composable
fun AutomotiveRadialGauge(
    value: Double,
    maxValue: Double,
    unitLabel: String,
    title: String,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = (value / maxValue).coerceIn(0.0, 1.0).toFloat()

    Box(
        modifier = modifier.size(148.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(136.dp)) {
            val strokeWidth = 12.dp.toPx()
            val arcDiameter = size.minDimension - strokeWidth
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
            val arcSize = Size(arcDiameter, arcDiameter)

            // Background track arc (150 deg to 390 deg -> 240 deg sweep)
            drawArc(
                color = Color(0xFF1F2937),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Active value arc
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(primaryColor.copy(alpha = 0.7f), primaryColor),
                ),
                startAngle = 150f,
                sweepAngle = 240f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9CA3AF),
            )
            Text(
                text = "%.1f".format(java.util.Locale.US, value),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelSmall,
                color = primaryColor,
            )
        }
    }
}

/**
 * Custom Canvas-drawn top-down chassis Tire Pressure Monitoring System (TPMS) diagram.
 * Highlights any tire below the 1.9 bar safety threshold in amber/red.
 */
@Composable
fun TpmsChassisDiagram(
    tires: TirePressureSnapshot,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF111827), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TirePressureBadge(position = "FL (Front Left)", bar = tires.frontLeftBar)
            TirePressureBadge(position = "RL (Rear Left)", bar = tires.rearLeftBar)
        }

        Canvas(
            modifier = Modifier
                .height(96.dp)
                .size(width = 64.dp, height = 96.dp),
        ) {
            // Vehicle chassis silhouette
            drawRoundRect(
                color = Color(0xFF374151),
                topLeft = Offset(size.width * 0.2f, size.height * 0.08f),
                size = Size(size.width * 0.6f, size.height * 0.84f),
                cornerRadius = CornerRadius(18f, 18f),
                style = Stroke(width = 3.dp.toPx()),
            )
            // Axles
            drawLine(
                color = Color(0xFF4B5563),
                start = Offset(size.width * 0.1f, size.height * 0.25f),
                end = Offset(size.width * 0.9f, size.height * 0.25f),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = Color(0xFF4B5563),
                start = Offset(size.width * 0.1f, size.height * 0.75f),
                end = Offset(size.width * 0.9f, size.height * 0.75f),
                strokeWidth = 2.dp.toPx(),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TirePressureBadge(position = "FR (Front Right)", bar = tires.frontRightBar)
            TirePressureBadge(position = "RR (Rear Right)", bar = tires.rearRightBar)
        }
    }
}

@Composable
private fun TirePressureBadge(position: String, bar: Double) {
    val isLow = bar < 1.9
    val color = if (isLow) Color(0xFFF87171) else Color(0xFF34D399)
    Column {
        Text(text = position, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9CA3AF))
        Text(
            text = "%.2f bar".format(java.util.Locale.US, bar),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
