package com.masaibar.datastore.inspector.sample

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.masaibar.datastore.inspector.sample.ShowcaseStore.retryOnReadFailure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

class ShowcaseSettingsSpec :
  DescribeSpec({
    describe("Preferences.toShowcaseSettings") {
      context("when the store is empty") {
        it("falls back to the defaults") {
          emptyPreferences().toShowcaseSettings() shouldBe ShowcaseSettings()
        }
      }

      context("when every key is stored") {
        it("maps each key to its typed value") {
          val preferences =
            preferencesOf(
              ShowcaseKeys.THEME to "dark",
              ShowcaseKeys.ACCENT to "purple",
              ShowcaseKeys.ONBOARDING_DONE to true,
              ShowcaseKeys.LAUNCH_COUNT to 7
            )

          preferences.toShowcaseSettings() shouldBe
            ShowcaseSettings(
              theme = ShowcaseTheme.DARK,
              accent = ShowcaseAccent.PURPLE,
              onboardingDone = true,
              launchCount = 7
            )
        }
      }

      context("when a stored value is not a known option") {
        it("falls back to the default for that key only") {
          val preferences =
            preferencesOf(
              ShowcaseKeys.THEME to "sepia",
              ShowcaseKeys.ACCENT to "orange"
            )

          preferences.toShowcaseSettings() shouldBe
            ShowcaseSettings(theme = ShowcaseTheme.LIGHT, accent = ShowcaseAccent.ORANGE)
        }
      }
    }

    describe("ShowcaseStore.corruptionHandler") {
      context("when the stored file cannot be parsed") {
        it("reads the defaults and keeps accepting later edits") {
          val file = File(tempdir(), "showcase_settings.preferences_pb")
          file.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37))
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          try {
            val dataStore =
              PreferenceDataStoreFactory.create(
                corruptionHandler = ShowcaseStore.corruptionHandler,
                scope = scope,
                produceFile = { file }
              )

            dataStore.data.first().toShowcaseSettings() shouldBe ShowcaseSettings()

            dataStore.edit { preferences -> preferences[ShowcaseKeys.THEME] = "dark" }
            dataStore.data.first().toShowcaseSettings().theme shouldBe ShowcaseTheme.DARK
          } finally {
            scope.cancel()
          }
        }
      }
    }

    describe("ShowcaseStore.retryOnReadFailure") {
      context("when the first read fails with an IOException") {
        it("shows the defaults, reads again, and keeps following later edits") {
          val stored = MutableStateFlow(preferencesOf(ShowcaseKeys.THEME to "dark"))
          var readAttempts = 0
          val data =
            flow {
              readAttempts += 1
              if (readAttempts == 1) throw IOException("temporary read failure")
              emitAll(stored)
            }

          val themes =
            withTimeout(5_000) {
              data
                .retryOnReadFailure(retryDelayMillis = 1)
                .map { preferences -> preferences.toShowcaseSettings().theme }
                .onEach { theme ->
                  if (theme == ShowcaseTheme.DARK) {
                    stored.value = preferencesOf(ShowcaseKeys.THEME to "light")
                  }
                }
                .take(3)
                .toList()
            }

          themes shouldBe listOf(ShowcaseTheme.LIGHT, ShowcaseTheme.DARK, ShowcaseTheme.LIGHT)
          readAttempts shouldBe 2
        }
      }

      context("when the read fails with something other than an IOException") {
        it("rethrows the failure instead of retrying") {
          val failure = IllegalStateException("not a read failure")
          val data = flow<Preferences> { throw failure }

          shouldThrow<IllegalStateException> {
            data.retryOnReadFailure(retryDelayMillis = 1).first()
          } shouldBeSameInstanceAs failure
        }
      }
    }

    describe("ShowcaseTheme.toggled") {
      it("switches between light and dark") {
        ShowcaseTheme.LIGHT.toggled() shouldBe ShowcaseTheme.DARK
        ShowcaseTheme.DARK.toggled() shouldBe ShowcaseTheme.LIGHT
      }
    }

    describe("ShowcaseAccent.next") {
      it("cycles through every accent and wraps around") {
        ShowcaseAccent.TEAL.next() shouldBe ShowcaseAccent.ORANGE
        ShowcaseAccent.ORANGE.next() shouldBe ShowcaseAccent.PURPLE
        ShowcaseAccent.PURPLE.next() shouldBe ShowcaseAccent.TEAL
      }
    }
  })
