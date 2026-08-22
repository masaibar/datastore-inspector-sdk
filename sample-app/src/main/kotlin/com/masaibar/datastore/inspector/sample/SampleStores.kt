package com.masaibar.datastore.inspector.sample

import android.content.Context
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.protobuf.ByteString
import com.masaibar.datastore.inspector.sample.proto.Theme
import com.masaibar.datastore.inspector.sample.proto.UserSettings
import com.masaibar.datastore.inspector.sample.proto.common.Profile
import java.io.InputStream
import java.io.OutputStream

public val Context.primaryPreferences by preferencesDataStore(name = "primary_preferences")
public val Context.secondaryPreferences by preferencesDataStore(name = "secondary_preferences")
public val Context.userSettings by
  dataStore(fileName = "user_settings.pb", serializer = UserSettingsSerializer)

public object UserSettingsSerializer : Serializer<UserSettings> {
  override val defaultValue: UserSettings = UserSettings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): UserSettings = UserSettings.parseFrom(input)

  override suspend fun writeTo(t: UserSettings, output: OutputStream) {
    t.writeTo(output)
  }
}

public object SampleAppUpdates {
  public suspend fun writeAllPreferenceTypes(context: Context) {
    context.primaryPreferences.edit { preferences ->
      preferences[stringPreferencesKey("string")] = "日本語"
      preferences[intPreferencesKey("int")] = 42
      preferences[longPreferencesKey("long")] = Long.MAX_VALUE
      preferences[floatPreferencesKey("float")] = -0.0f
      preferences[doublePreferencesKey("double")] = Double.NaN
      preferences[booleanPreferencesKey("boolean")] = true
      preferences[stringSetPreferencesKey("string_set")] = setOf("alpha", "beta")
      preferences[byteArrayPreferencesKey("bytes")] = byteArrayOf(0, 1, 2, -1)
    }
    context.secondaryPreferences.edit { preferences ->
      preferences[stringPreferencesKey("source")] = "secondary"
    }
  }

  public suspend fun updateProtoFromApp(context: Context) {
    context.userSettings.updateData { current ->
      val nextLaunchCount = current.launchCount + 1
      val builder =
        current.toBuilder()
          .setUserName("sample-user")
          .setLaunchCount(nextLaunchCount)
          .setLastLoginEpochMillis(System.currentTimeMillis())
          .setPremium(true)
          .setRating(4.5f)
          .setBalance(1234.56)
          .setUint32Value(UInt.MAX_VALUE.toInt())
          .setUint64Value(ULong.MAX_VALUE.toLong())
          .setSint32Value(Int.MIN_VALUE)
          .setSint64Value(Long.MIN_VALUE)
          .setFixed32Value(UInt.MAX_VALUE.toInt())
          .setFixed64Value(ULong.MAX_VALUE.toLong())
          .setSfixed32Value(Int.MAX_VALUE)
          .setSfixed64Value(Long.MAX_VALUE)
          .setTheme(Theme.DARK)
          .setProfile(
            Profile.newBuilder()
              .setDisplayName("Sample User")
              .setAge(30)
          )
          .clearEnabledFeatures()
          .addAllEnabledFeatures(listOf("inspector", "live-edit"))
          .clearFeatureFlags()
          .putAllFeatureFlags(mapOf("dark_mode" to true, "beta" to false))
          .setNickname("sample")
          .setRawValue(ByteString.copyFrom(byteArrayOf(0, 1, 2, -1)))
          .setNotifications(
            UserSettings.NotificationSettings.newBuilder()
              .setEnabled(true)
              .addAllChannels(listOf("updates", "alerts"))
          )
          .clearUnsignedIds()
          .addAllUnsignedIds(listOf(0L, Long.MIN_VALUE, ULong.MAX_VALUE.toLong()))
          .clearNotificationProfiles()
          .addNotificationProfiles(
            UserSettings.NotificationSettings.newBuilder()
              .setEnabled(true)
              .addChannels("first")
          )
          .addNotificationProfiles(
            UserSettings.NotificationSettings.newBuilder()
              .setEnabled(false)
              .addChannels("second")
          )
          .clearUnsignedCounters()
          .putAllUnsignedCounters(
            mapOf(
              0 to 0L,
              UInt.MAX_VALUE.toInt() to ULong.MAX_VALUE.toLong()
            )
          )
          .clearNotificationRoutes()
          .putNotificationRoutes(
            "primary",
            UserSettings.NotificationSettings.newBuilder()
              .setEnabled(true)
              .addChannels("push")
              .build()
          )

      if (current.destinationCase == UserSettings.DestinationCase.SCREEN_NAME) {
        builder.setContentId(42L + nextLaunchCount)
      } else {
        builder.setScreenName("settings")
      }
      builder.build()
    }
  }
}
