package com.masaibar.datastore.inspector.sample

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.masaibar.datastore.inspector.sample.proto.UserSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SampleViewModel(
  application: Application
) : AndroidViewModel(application) {
  sealed interface Action {
    val label: String

    data object UpdatePreferences : Action {
      override val label: String = "Update Preferences"
    }

    data object UpdateSharedPreferences : Action {
      override val label: String = "Update SharedPreferences"
    }

    data object UpdateProto : Action {
      override val label: String = "Update Proto"
    }
  }

  private data class OperationState(
    val runningAction: Action? = null,
    val resultMessage: String = "Ready."
  )

  private data class CurrentValues(
    val primary: Preferences,
    val secondary: Preferences,
    val proto: UserSettings
  ) {
    fun preferencesSummary(): String = buildString {
      appendLine("primary_preferences")
      appendLine(primary.summary().prependIndent("  "))
      appendLine()
      appendLine("secondary_preferences")
      append(secondary.summary().prependIndent("  "))
    }
  }

  private val operationState = MutableStateFlow(OperationState())
  private val sharedPreferencesValues =
    MutableStateFlow("Loading SharedPreferences…")
  private val sharedPreferencesRefreshRequests = Channel<Unit>(Channel.CONFLATED)
  private val sharedPreferencesObservation =
    SampleSharedPreferences.observe(application) {
      sharedPreferencesRefreshRequests.trySend(Unit)
    }

  init {
    viewModelScope.launch {
      sharedPreferencesValues.value =
        runCatching {
          SampleSharedPreferences.initialize(getApplication())
        }.getOrElse {
          "Could not initialize the SharedPreferences sample."
        }
      sharedPreferencesRefreshRequests.consumeEach {
        sharedPreferencesValues.value =
          runCatching {
            SampleSharedPreferences.currentSummary(getApplication())
          }.getOrElse {
            "Could not reload SharedPreferences."
          }
      }
    }
  }

  private val currentValues =
    combine(
      application.primaryPreferences.data,
      application.secondaryPreferences.data,
      application.userSettings.data
    ) { primary, secondary, proto ->
      CurrentValues(
        primary = primary,
        secondary = secondary,
        proto = proto
      )
    }

  val uiState: StateFlow<SampleUiState> =
    combine(
      currentValues,
      operationState,
      sharedPreferencesValues
    ) { values, operation, sharedPreferences ->
      SampleUiState(
        preferencesValues = values.preferencesSummary(),
        sharedPreferencesValues = sharedPreferences,
        protoValues = values.proto.summary(),
        resultMessage = operation.resultMessage,
        isOperationRunning = operation.runningAction != null
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = SampleUiState.initialValue()
    )

  fun onAction(action: Action) {
    if (operationState.value.runningAction != null) return

    when (action) {
      Action.UpdatePreferences -> launchUpdate(action) {
        SampleAppUpdates.writeAllPreferenceTypes(getApplication())
      }

      Action.UpdateSharedPreferences -> launchUpdate(action) {
        sharedPreferencesValues.value =
          SampleSharedPreferences.updateFromApp(getApplication())
      }

      Action.UpdateProto -> launchUpdate(action) {
        SampleAppUpdates.updateProtoFromApp(getApplication())
      }
    }
  }

  override fun onCleared() {
    sharedPreferencesObservation.close()
    sharedPreferencesRefreshRequests.close()
    super.onCleared()
  }

  private fun launchUpdate(
    action: Action,
    update: suspend () -> Unit
  ) {
    operationState.update { it.copy(runningAction = action) }
    viewModelScope.launch {
      val result = runCatching { update() }
      operationState.value =
        OperationState(
          resultMessage =
            result.fold(
              onSuccess = { "${action.label}: complete" },
              onFailure = { "${action.label}: failed" }
            )
        )
    }
  }
}

internal data class SampleUiState(
  val preferencesValues: String,
  val sharedPreferencesValues: String,
  val protoValues: String,
  val resultMessage: String,
  val isOperationRunning: Boolean
) {
  companion object {
    fun initialValue(): SampleUiState =
      SampleUiState(
        preferencesValues = "Loading…",
        sharedPreferencesValues = "Loading…",
        protoValues = "Loading…",
        resultMessage = "Ready.",
        isOperationRunning = false
      )
  }
}

private fun Preferences.summary(): String =
  asMap().entries
    .sortedBy { (key, _) -> key.name }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator = "\n") { (key, value) ->
      "${key.name}: ${value.displayValue()}"
    }
    ?: "(empty)"

private fun Any.displayValue(): String =
  when (this) {
    is ByteArray -> joinToString(prefix = "0x", separator = "") { byte -> "%02x".format(byte) }
    is Set<*> -> map { it.toString() }.sorted().joinToString(prefix = "[", postfix = "]")
    else -> toString()
  }

internal fun UserSettings.summary(): String = buildString {
  appendLine("user_name: $userName")
  appendLine("launch_count: $launchCount")
  appendLine("last_login_epoch_millis: $lastLoginEpochMillis")
  appendLine("premium: $premium")
  appendLine("rating: $rating")
  appendLine("balance: $balance")
  appendLine("uint32_value: ${Integer.toUnsignedString(uint32Value)}")
  appendLine("uint64_value: ${java.lang.Long.toUnsignedString(uint64Value)}")
  appendLine("sint32_value: $sint32Value")
  appendLine("sint64_value: $sint64Value")
  appendLine("fixed32_value: ${Integer.toUnsignedString(fixed32Value)}")
  appendLine("fixed64_value: ${java.lang.Long.toUnsignedString(fixed64Value)}")
  appendLine("sfixed32_value: $sfixed32Value")
  appendLine("sfixed64_value: $sfixed64Value")
  appendLine("theme: $theme (value=$themeValue)")
  appendLine("profile.present: ${hasProfile()}")
  appendLine("profile.display_name: ${profile.displayName}")
  appendLine("profile.age: ${profile.age}")
  appendLine("enabled_features: ${enabledFeaturesList.joinToString(prefix = "[", postfix = "]")}")
  appendLine(
    "feature_flags: " +
      featureFlagsMap.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "$key=$value"
      }
  )
  appendLine("nickname.present: ${hasNickname()}")
  appendLine("nickname: $nickname")
  appendLine("destination.case: $destinationCase")
  appendLine(
    "destination.screen_name: " +
      if (destinationCase == UserSettings.DestinationCase.SCREEN_NAME) screenName else "<inactive>"
  )
  appendLine(
    "destination.content_id: " +
      if (destinationCase == UserSettings.DestinationCase.CONTENT_ID) contentId else "<inactive>"
  )
  appendLine("raw_value: ${rawValue.toByteArray().displayValue()}")
  appendLine("notifications.present: ${hasNotifications()}")
  appendLine("notifications.enabled: ${notifications.enabled}")
  appendLine("notifications.channels: ${notifications.channelsList.joinToString(prefix = "[", postfix = "]")}")
  appendLine(
    "unsigned_ids: " +
      unsignedIdsList.joinToString(prefix = "[", postfix = "]") {
        java.lang.Long.toUnsignedString(it)
      }
  )
  appendLine(
    "notification_profiles: " +
      notificationProfilesList.joinToString(prefix = "[", postfix = "]") {
        "{enabled=${it.enabled}, channels=${it.channelsList}}"
      }
  )
  appendLine(
    "unsigned_counters: " +
      unsignedCountersMap.entries
        .sortedBy { (key, _) -> Integer.toUnsignedLong(key) }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
          "${Integer.toUnsignedString(key)}=${java.lang.Long.toUnsignedString(value)}"
        }
  )
  append(
    "notification_routes: " +
      notificationRoutesMap.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "$key={enabled=${value.enabled}, channels=${value.channelsList}}"
      }
  )
}
