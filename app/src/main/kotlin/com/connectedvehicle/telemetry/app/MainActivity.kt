package com.connectedvehicle.telemetry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.connectedvehicle.telemetry.feature.cockpit.CockpitMviController
import com.connectedvehicle.telemetry.feature.cockpit.ui.ConnectedVehicleCockpitScreen

class MainActivity : ComponentActivity() {
    private val cockpitController = CockpitMviController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ConnectedVehicleCockpitScreen(controller = cockpitController)
            }
        }
    }
}
