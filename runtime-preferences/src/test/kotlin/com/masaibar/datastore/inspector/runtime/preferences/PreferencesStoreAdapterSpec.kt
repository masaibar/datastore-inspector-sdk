package com.masaibar.datastore.inspector.runtime.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.masaibar.datastore.inspector.protocol.BytesValue
import com.masaibar.datastore.inspector.protocol.FloatValue
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.MutatePreferences
import com.masaibar.datastore.inspector.protocol.PreferenceEntry
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.PutPreference
import com.masaibar.datastore.inspector.protocol.ReplacePreferences
import com.masaibar.datastore.inspector.protocol.StringSetValue
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class PreferencesStoreAdapterSpec : DescribeSpec() {
  init {
    describe("PreferencesStoreAdapter") {
      context("既存の契約を検証するとき") {
        it("全Preferences型を決定的な順序とraw bitsでsnapshot化する") {
          val store = FakeDataStore(
            preferencesOf(
              stringPreferencesKey("string") to "日本語",
              intPreferencesKey("int") to 42,
              longPreferencesKey("long") to Long.MAX_VALUE,
              floatPreferencesKey("float") to -0.0f,
              doublePreferencesKey("double") to Double.NaN,
              booleanPreferencesKey("boolean") to true,
              stringSetPreferencesKey("set") to setOf("beta", "alpha"),
              byteArrayPreferencesKey("bytes") to byteArrayOf(0, 1, -1)
            )
          )
          val adapter = PreferencesStoreAdapter(store)
          val first = adapter.snapshot()
          val second = adapter.snapshot()
          val tree = (first.payload).shouldBeInstanceOf<PreferencesTree>()

          (tree.root.children.map { it.name }) shouldBe (tree.root.children.map { it.name }.sorted())
          (second.fingerprint) shouldBe (first.fingerprint)
          ((tree.root.children.first { it.name == "bytes" }.value as com.masaibar.datastore.inspector.protocol.BytesValue).value).toList() shouldBe (byteArrayOf(0, 1, -1)).toList()
        }

        it("transaction内でapp write競合を検出して変更しない") {
          val key = intPreferencesKey("count")
          val store = FakeDataStore(preferencesOf(key to 1))
          val adapter = PreferencesStoreAdapter(store)
          val before = adapter.snapshot()
          store.updateData { preferencesOf(key to 2) }

          val result = adapter.write(before.fingerprint, MutatePreferences(PutPreference("count", IntValue(3))))

          (result).shouldBeInstanceOf<AdapterWriteResult.Conflict>()
          (store.value[key]) shouldBe (2)
        }

        it("同名keyの型違いはtransaction全体を失敗させる") {
          val store = FakeDataStore(preferencesOf(stringPreferencesKey("value") to "text"))
          val adapter = PreferencesStoreAdapter(store)
          val before = adapter.snapshot()

          shouldThrow<IllegalArgumentException> {
            adapter.write(before.fingerprint, MutatePreferences(PutPreference("value", IntValue(1))))
          }
          (store.value[stringPreferencesKey("value")]) shouldBe ("text")
        }

        it("StringSetとByteArrayと特殊Floatをtransactionで書き込む") {
          val store = FakeDataStore(preferencesOf())
          val adapter = PreferencesStoreAdapter(store)

          suspend fun write(
            key: String,
            value: com.masaibar.datastore.inspector.protocol.InspectorValue
          ) {
            val before = adapter.snapshot()
            (adapter.write(before.fingerprint, MutatePreferences(PutPreference(key, value)))).shouldBeInstanceOf<AdapterWriteResult.Applied>()
          }

          write("set", StringSetValue(listOf("beta", "alpha")))
          write("bytes", BytesValue(byteArrayOf(0, 1, -1)))
          write("nan", FloatValue(Float.NaN.toRawBits().toUInt().toString(16)))
          write("negative_zero", FloatValue((-0.0f).toRawBits().toUInt().toString(16)))

          (store.value[stringSetPreferencesKey("set")]) shouldBe (setOf("alpha", "beta"))
          store.value[byteArrayPreferencesKey("bytes")].shouldNotBeNull().toList() shouldBe
            byteArrayOf(0, 1, -1).toList()
          (store.value[floatPreferencesKey("nan")]?.toRawBits()) shouldBe (Float.NaN.toRawBits())
          (store.value[floatPreferencesKey("negative_zero")]?.toRawBits()) shouldBe ((-0.0f).toRawBits())
        }

        it("全置換は省略keyを削除し型変更を含む全entryを一回のtransactionで反映する") {
          val store =
            FakeDataStore(
              preferencesOf(
                intPreferencesKey("count") to 1,
                stringPreferencesKey("remove") to "old"
              )
            )
          val adapter = PreferencesStoreAdapter(store)
          val before = adapter.snapshot()

          val result =
            adapter.write(
              before.fingerprint,
              ReplacePreferences(
                listOf(
                  PreferenceEntry("bytes", BytesValue(byteArrayOf(0, 1, -1))),
                  PreferenceEntry("count", StringValue("retyped")),
                  PreferenceEntry("set", StringSetValue(listOf("alpha", "beta")))
                )
              )
            )

          (result).shouldBeInstanceOf<AdapterWriteResult.Applied>()
          (store.updateCount) shouldBe (1)
          (store.value[stringPreferencesKey("remove")]) shouldBe (null)
          (store.value[stringPreferencesKey("count")]) shouldBe ("retyped")
          (store.value[stringSetPreferencesKey("set")]) shouldBe (setOf("alpha", "beta"))
          store.value[byteArrayPreferencesKey("bytes")].shouldNotBeNull().toList() shouldBe
            byteArrayOf(0, 1, -1).toList()
        }
      }
    }
  }

  private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    val value: Preferences get() = state.value
    var updateCount: Int = 0
      private set
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      updateCount += 1
      val updated = transform(state.value)
      state.value = updated
      return updated
    }
  }
}
