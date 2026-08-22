package com.masaibar.datastore.inspector.sample

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

internal object SampleSharedPreferences {
  private const val SAMPLE_NAME = "sample_preferences"
  private const val INITIALIZED_KEY = "sample_initialized"
  private val appUpdateCounter = AtomicInteger()

  suspend fun initialize(context: Context): String =
    withContext(Dispatchers.IO) {
      val preferences = context.getSharedPreferences(SAMPLE_NAME, Context.MODE_PRIVATE)
      if (!preferences.contains(INITIALIZED_KEY)) {
        check(
          preferences.edit()
            .putString("string", "Hello from the sample app 👋")
            .putInt("int", 42)
            .putLong("long", 1_234_567_890L)
            .putFloat("float", 4.5f)
            .putBoolean("boolean", true)
            .putStringSet("string_set", linkedSetOf("alpha", "beta"))
            .putBoolean(INITIALIZED_KEY, true)
            .commit()
        ) {
          "Could not persist the SharedPreferences sample."
        }
      }
      preferences.summary()
    }

  suspend fun updateFromApp(context: Context): String =
    withContext(Dispatchers.IO) {
      val preferences = fixturePreferences(context)
      val count = appUpdateCounter.incrementAndGet()
      check(
        preferences.edit()
          .putInt("app_update_count", count)
          .putString("string", "Updated by the sample app ($count)")
          .commit()
      ) {
        "Could not persist the SharedPreferences update."
      }
      preferences.summary()
    }

  suspend fun currentSummary(context: Context): String =
    withContext(Dispatchers.IO) {
      fixturePreferences(context).summary()
    }

  fun observe(
    context: Context,
    onChanged: () -> Unit
  ): Closeable {
    val preferences = fixturePreferences(context)
    val listener =
      SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        onChanged()
      }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    return Closeable {
      preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
  }

  private fun fixturePreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(SAMPLE_NAME, Context.MODE_PRIVATE)

  private fun SharedPreferences.summary(): String =
    all.entries
      .sortedBy { (key, _) -> key }
      .takeIf { it.isNotEmpty() }
      ?.joinToString(separator = "\n") { (key, value) ->
        "$key: ${value.displaySharedPreferencesValue()}"
      }
      ?: "(empty)"

  private fun Any?.displaySharedPreferencesValue(): String =
    when (this) {
      is String -> this
      is Set<*> ->
        map { element -> element.toString() }
          .sorted()
          .joinToString(prefix = "[", postfix = "]")
      else -> toString()
    }
}
