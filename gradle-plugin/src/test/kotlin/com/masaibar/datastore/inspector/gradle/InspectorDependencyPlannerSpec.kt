package com.masaibar.datastore.inspector.gradle

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class InspectorDependencyPlannerSpec :
  DescribeSpec({
    describe("runtimeArtifacts") {
      context("Preferences・Proto・両方・Customのsignalがあるとき") {
        val preferences = DependencySignals(preferences = true)
        val protobuf = DependencySignals(protobuf = true)
        val both = DependencySignals(preferences = true, protobuf = true)
        val custom = DependencySignals()

        it("optional Adapter依存を4構成で選択する") {
          InspectorDependencyPlanner.runtimeArtifacts(preferences) shouldBe
            listOf("runtime-core", "runtime-shared-preferences", "runtime-preferences")
          InspectorDependencyPlanner.runtimeArtifacts(protobuf) shouldBe
            listOf("runtime-core", "runtime-shared-preferences", "runtime-protobuf")
          InspectorDependencyPlanner.runtimeArtifacts(both) shouldBe
            listOf(
              "runtime-core",
              "runtime-shared-preferences",
              "runtime-preferences",
              "runtime-protobuf"
            )
          InspectorDependencyPlanner.runtimeArtifacts(custom) shouldBe
            listOf("runtime-core", "runtime-shared-preferences")
        }
      }
    }

    describe("observe") {
      context("Preferences・protobuf・無関係な依存座標があるとき") {
        lateinit var signals: DependencySignals

        beforeEach {
          signals = DependencySignals()
        }

        it("Preferencesとprotobuf signalだけを検出する") {
          InspectorDependencyPlanner.observe(
            "androidx.datastore",
            "datastore-preferences-core",
            signals
          )
          InspectorDependencyPlanner.observe("com.google.protobuf", "protobuf-javalite", signals)
          InspectorDependencyPlanner.observe("dev.example", "custom-serializer", signals)

          signals shouldBe DependencySignals(preferences = true, protobuf = true)
        }
      }
    }
  })
