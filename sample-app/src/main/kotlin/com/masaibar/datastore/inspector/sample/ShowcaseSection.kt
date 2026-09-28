package com.masaibar.datastore.inspector.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Large, high-contrast controls bound to [ShowcaseSettings].
 *
 * Every value is rendered next to its DataStore key so that a viewer can match what the app
 * shows with what DataStore Inspector shows, even in a small screen recording.
 */
@Composable
internal fun ShowcaseSection(
  settings: ShowcaseSettings,
  onAction: (SampleViewModel.Action) -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
      )
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "Showcase",
          style = MaterialTheme.typography.titleLarge
        )
        Text(
          text = "showcase_settings · edit these keys from DataStore Inspector",
          style = MaterialTheme.typography.bodyMedium
        )
      }

      ShowcaseValueRow(key = "theme", value = settings.theme.storedValue) {
        ShowcaseButton(label = "Toggle") {
          onAction(SampleViewModel.Action.SetShowcaseTheme(settings.theme.toggled()))
        }
      }

      ShowcaseValueRow(key = "accent", value = settings.accent.storedValue) {
        ShowcaseButton(label = "Next") {
          onAction(SampleViewModel.Action.SetShowcaseAccent(settings.accent.next()))
        }
      }

      ShowcaseValueRow(key = "onboarding_done", value = settings.onboardingDone.toString()) {
        Switch(
          checked = settings.onboardingDone,
          onCheckedChange = { checked ->
            onAction(SampleViewModel.Action.SetShowcaseOnboardingDone(checked))
          }
        )
      }

      ShowcaseValueRow(key = "launch_count", value = settings.launchCount.toString()) {}
    }
  }
}

@Composable
private fun ShowcaseValueRow(
  key: String,
  value: String,
  control: @Composable () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = key,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        text = value,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.displaySmall
      )
    }
    control()
  }
}

@Composable
private fun ShowcaseButton(
  label: String,
  onClick: () -> Unit
) {
  Button(
    onClick = onClick,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.onPrimary,
        contentColor = MaterialTheme.colorScheme.primary
      )
  ) {
    Text(label)
  }
}

/** The whole sample screen follows the stored theme and accent so an edit is visible without navigating anywhere. */
internal fun ShowcaseSettings.colorScheme(): ColorScheme {
  val accentColor = accent.color()
  return when (theme) {
    ShowcaseTheme.LIGHT ->
      lightColorScheme(
        primary = accentColor,
        onPrimary = Color.White
      )

    ShowcaseTheme.DARK ->
      darkColorScheme(
        primary = accentColor,
        onPrimary = Color.White
      )
  }
}

private fun ShowcaseAccent.color(): Color =
  when (this) {
    ShowcaseAccent.TEAL -> Color(0xFF00897B)
    ShowcaseAccent.ORANGE -> Color(0xFFEF6C00)
    ShowcaseAccent.PURPLE -> Color(0xFF7E57C2)
  }
