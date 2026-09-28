package com.masaibar.datastore.inspector.sample

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      val viewModel: SampleViewModel = viewModel()
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      val darkTheme = uiState.showcase.theme == ShowcaseTheme.DARK
      LaunchedEffect(darkTheme) {
        // The stored theme, not the system setting, decides the system bar icon contrast.
        enableEdgeToEdge(
          statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
          navigationBarStyle = SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { darkTheme }
        )
      }
      SampleScreen(
        uiState = uiState,
        onAction = viewModel::onAction
      )
    }
  }

  private companion object {
    // Same scrims as androidx.activity uses by default; only needed where the navigation bar cannot be transparent.
    const val LIGHT_NAVIGATION_SCRIM = 0xE6FFFFFF.toInt()
    const val DARK_NAVIGATION_SCRIM = 0x801B1B1B.toInt()
  }
}
