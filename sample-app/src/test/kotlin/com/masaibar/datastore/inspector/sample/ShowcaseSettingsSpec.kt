package com.masaibar.datastore.inspector.sample

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

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
