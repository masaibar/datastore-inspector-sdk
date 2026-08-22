package com.masaibar.datastore.inspector.runtime.preferences

import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.runtime.core.DataStoreInspectorRuntime
import com.masaibar.datastore.inspector.runtime.core.RegistryState
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PreferencesDataStoreDelegateBridgeSpec :
  DescribeSpec({
    describe("preferencesDataStoreDefault") {
      context("default引数maskを持つPreferences宣言のとき") {
        val declarationId = "preferences-bridge-test-${System.nanoTime()}"

        it("default引数を復元してPreferences宣言を登録する") {
          PreferencesDataStoreDelegateBridge.preferencesDataStoreDefault(
            name = "settings",
            corruptionHandler = null,
            produceMigrations = null,
            scope = null,
            mask = 14,
            marker = null,
            declarationId = declarationId,
            declarationOwner = "sample.PreferenceStores",
            declarationProperty = "settings"
          )

          val entry =
            DataStoreInspectorRuntime.registry().entries().single {
              it.declaration.declarationId == declarationId
            }
          entry.state.shouldBeInstanceOf<RegistryState.Declared>()
          entry.declaration.kindHint shouldBe StoreKind.PREFERENCES
          entry.declaration.fileName shouldBe "settings"
          entry.declaration.owner shouldBe "sample.PreferenceStores"
          entry.declaration.property shouldBe "settings"
        }
      }
    }
  })
