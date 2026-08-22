package com.masaibar.datastore.inspector.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MaterialTheme {
        val viewModel: SampleViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        SampleScreen(
          uiState = uiState,
          onAction = viewModel::onAction
        )
      }
    }
  }
}
