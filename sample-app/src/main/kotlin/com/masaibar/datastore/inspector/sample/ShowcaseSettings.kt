package com.masaibar.datastore.inspector.sample

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Preferences DataStore behind the showcase section of the sample screen.
 *
 * The store holds only a handful of short keys so that every key stays readable while
 * the app and DataStore Inspector are shown side by side.
 */
public val Context.showcaseSettings: DataStore<Preferences> by
  preferencesDataStore(name = "showcase_settings")

public enum class ShowcaseTheme(public val storedValue: String) {
  LIGHT("light"),
  DARK("dark");

  public fun toggled(): ShowcaseTheme = if (this == LIGHT) DARK else LIGHT

  public companion object {
    public fun fromStoredValue(value: String?): ShowcaseTheme =
      entries.firstOrNull { it.storedValue == value } ?: LIGHT
  }
}

public enum class ShowcaseAccent(public val storedValue: String) {
  TEAL("teal"),
  ORANGE("orange"),
  PURPLE("purple");

  public fun next(): ShowcaseAccent = entries[(ordinal + 1) % entries.size]

  public companion object {
    public fun fromStoredValue(value: String?): ShowcaseAccent =
      entries.firstOrNull { it.storedValue == value } ?: TEAL
  }
}

public data class ShowcaseSettings(
  val theme: ShowcaseTheme = ShowcaseTheme.LIGHT,
  val accent: ShowcaseAccent = ShowcaseAccent.TEAL,
  val onboardingDone: Boolean = false,
  val launchCount: Int = 0
)

public object ShowcaseKeys {
  public val THEME: Preferences.Key<String> = stringPreferencesKey("theme")
  public val ACCENT: Preferences.Key<String> = stringPreferencesKey("accent")
  public val ONBOARDING_DONE: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_done")
  public val LAUNCH_COUNT: Preferences.Key<Int> = intPreferencesKey("launch_count")
}

/** Unknown or missing values fall back to the defaults of [ShowcaseSettings]. */
public fun Preferences.toShowcaseSettings(): ShowcaseSettings =
  ShowcaseSettings(
    theme = ShowcaseTheme.fromStoredValue(this[ShowcaseKeys.THEME]),
    accent = ShowcaseAccent.fromStoredValue(this[ShowcaseKeys.ACCENT]),
    onboardingDone = this[ShowcaseKeys.ONBOARDING_DONE] ?: ShowcaseSettings().onboardingDone,
    launchCount = this[ShowcaseKeys.LAUNCH_COUNT] ?: ShowcaseSettings().launchCount
  )

/** All writes go through [DataStore.edit], so edits made from DataStore Inspector and from the app share one Flow. */
public object ShowcaseStore {
  public fun settings(context: Context): Flow<ShowcaseSettings> =
    context.showcaseSettings.data.map { preferences -> preferences.toShowcaseSettings() }

  /**
   * Increments the launch counter and writes defaults for any key that is still missing,
   * so every showcase key is listed in the inspector from the first launch.
   */
  public suspend fun recordLaunch(context: Context) {
    val defaults = ShowcaseSettings()
    context.showcaseSettings.edit { preferences ->
      if (preferences[ShowcaseKeys.THEME] == null) {
        preferences[ShowcaseKeys.THEME] = defaults.theme.storedValue
      }
      if (preferences[ShowcaseKeys.ACCENT] == null) {
        preferences[ShowcaseKeys.ACCENT] = defaults.accent.storedValue
      }
      if (preferences[ShowcaseKeys.ONBOARDING_DONE] == null) {
        preferences[ShowcaseKeys.ONBOARDING_DONE] = defaults.onboardingDone
      }
      preferences[ShowcaseKeys.LAUNCH_COUNT] =
        (preferences[ShowcaseKeys.LAUNCH_COUNT] ?: defaults.launchCount) + 1
    }
  }

  public suspend fun setTheme(
    context: Context,
    theme: ShowcaseTheme
  ) {
    context.showcaseSettings.edit { preferences ->
      preferences[ShowcaseKeys.THEME] = theme.storedValue
    }
  }

  public suspend fun setAccent(
    context: Context,
    accent: ShowcaseAccent
  ) {
    context.showcaseSettings.edit { preferences ->
      preferences[ShowcaseKeys.ACCENT] = accent.storedValue
    }
  }

  public suspend fun setOnboardingDone(
    context: Context,
    done: Boolean
  ) {
    context.showcaseSettings.edit { preferences ->
      preferences[ShowcaseKeys.ONBOARDING_DONE] = done
    }
  }
}
