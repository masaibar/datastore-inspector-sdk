package com.masaibar.datastore.inspector.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun SampleScreen(
  uiState: SampleUiState,
  onAction: (SampleViewModel.Action) -> Unit
) {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Text(
          text = "DataStore Inspector SDK Sample",
          style = MaterialTheme.typography.headlineSmall
        )
        Text(
          text =
            "Run this debuggable app, then open DataStore Inspector in Android Studio. " +
              "The SDK is injected only into debuggable variants.",
          style = MaterialTheme.typography.bodyMedium
        )

        ValueSection("Preferences DataStore", uiState.preferencesValues)
        ActionRow {
          SampleActionButton(
            action = SampleViewModel.Action.UpdatePreferences,
            enabled = !uiState.isOperationRunning,
            onAction = onAction
          )
        }

        ValueSection("SharedPreferences", uiState.sharedPreferencesValues)
        ActionRow {
          SampleActionButton(
            action = SampleViewModel.Action.UpdateSharedPreferences,
            enabled = !uiState.isOperationRunning,
            onAction = onAction
          )
        }

        ValueSection("Proto DataStore", uiState.protoValues)
        ActionRow {
          SampleActionButton(
            action = SampleViewModel.Action.UpdateProto,
            enabled = !uiState.isOperationRunning,
            onAction = onAction
          )
        }

        Text(
          text = uiState.resultMessage,
          style = MaterialTheme.typography.bodyMedium
        )
      }
    }
  }
}

@Composable
private fun ValueSection(
  title: String,
  values: String
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        text = values,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
      )
    }
  }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    content()
  }
}

@Composable
private fun SampleActionButton(
  action: SampleViewModel.Action,
  enabled: Boolean,
  onAction: (SampleViewModel.Action) -> Unit
) {
  Button(
    onClick = { onAction(action) },
    enabled = enabled
  ) {
    Text(action.label)
  }
}

@Preview(showBackground = true)
@Composable
private fun SampleScreenPreview() {
  SampleScreen(
    uiState = SampleUiState.initialValue(),
    onAction = {}
  )
}
