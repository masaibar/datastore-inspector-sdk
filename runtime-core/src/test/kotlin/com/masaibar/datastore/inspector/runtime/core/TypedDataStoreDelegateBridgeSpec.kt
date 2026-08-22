package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.Serializer
import com.masaibar.datastore.inspector.protocol.StoreKind
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.InputStream
import java.io.OutputStream

class TypedDataStoreDelegateBridgeSpec :
  DescribeSpec({
    describe("dataStoreDefault") {
      context("default引数maskを持つcustom typed宣言のとき") {
        val declarationId = "typed-bridge-test-${System.nanoTime()}"

        it("default引数を復元してcustom typed宣言を登録する") {
          TypedDataStoreDelegateBridge.dataStoreDefault(
            name = "custom.bin",
            serializer = FixtureSerializer,
            corruptionHandler = null,
            produceMigrations = null,
            scope = null,
            mask = 28,
            marker = null,
            declarationId = declarationId,
            declarationOwner = "sample.CustomStores",
            declarationProperty = "customSettings"
          )

          val entry =
            DataStoreInspectorRuntime.registry().entries().single {
              it.declaration.declarationId == declarationId
            }
          entry.state.shouldBeInstanceOf<RegistryState.Declared>()
          entry.declaration.kindHint shouldBe StoreKind.CUSTOM
          entry.declaration.fileName shouldBe "custom.bin"
          entry.declaration.serializerClassName shouldBe FixtureSerializer.javaClass.name
          entry.declaration.valueClassName shouldBe FixtureValue::class.java.name
          entry.declaration.owner shouldBe "sample.CustomStores"
          entry.declaration.property shouldBe "customSettings"
        }
      }
    }
  })

private data class FixtureValue(val value: String)

private object FixtureSerializer : Serializer<FixtureValue> {
  override val defaultValue: FixtureValue = FixtureValue("初期値")

  override suspend fun readFrom(input: InputStream): FixtureValue = defaultValue

  override suspend fun writeTo(t: FixtureValue, output: OutputStream) = Unit
}
