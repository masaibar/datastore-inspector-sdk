package com.masaibar.datastore.inspector.runtime.protobuf

import androidx.datastore.core.DataStore
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.google.protobuf.StringValue
import com.masaibar.datastore.inspector.protocol.ReplaceProtoBytes
import com.masaibar.datastore.inspector.protocol.ResetStore
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class ProtobufStoreAdapterSpec : DescribeSpec() {
  init {
    describe("ProtobufStoreAdapter") {
      context("既存の契約を検証するとき") {
        it("raw bytesをsnapshot化しreplacement bytesをparserで検証する") {
          val store = FakeDataStore(StringValue.of("before"))
          val adapter = ProtobufStoreAdapter(store, schemaEntry())
          val before = adapter.snapshot()
          val replacement = StringValue.of("after").toByteArray()

          val result = adapter.write(before.fingerprint, ReplaceProtoBytes(adapter.schema, replacement))

          (result).shouldBeInstanceOf<AdapterWriteResult.Applied>()
          ((store.value as StringValue).value) shouldBe ("after")
          ((result.snapshot.payload as com.masaibar.datastore.inspector.protocol.ProtoRaw).valueBytes).toList() shouldBe (replacement).toList()
        }

        it("app write競合と不正replacementを変更なしで拒否する") {
          val store = FakeDataStore(StringValue.of("before"))
          val adapter = ProtobufStoreAdapter(store, schemaEntry())
          val before = adapter.snapshot()
          store.updateData { StringValue.of("app") }
          (
            adapter.write(
              before.fingerprint,
              ReplaceProtoBytes(adapter.schema, StringValue.of("client").toByteArray())
            )
          ).shouldBeInstanceOf<AdapterWriteResult.Conflict>()
          val current = adapter.snapshot()
          shouldThrow<InvalidProtocolBufferException> {
            adapter.write(current.fingerprint, ReplaceProtoBytes(adapter.schema, byteArrayOf(0x0a, 0x7f)))
          }
          ((store.value as StringValue).value) shouldBe ("app")
        }

        it("Store resetでdefault instanceへ戻す") {
          val store = FakeDataStore(StringValue.of("before"))
          val adapter = ProtobufStoreAdapter(store, schemaEntry())
          val before = adapter.snapshot()

          (adapter.write(before.fingerprint, ResetStore)).shouldBeInstanceOf<AdapterWriteResult.Applied>()
          ((store.value as StringValue).value) shouldBe ("")
        }

        it("実value型とschema indexの不一致を拒否する") {
          val store = FakeDataStore(StringValue.of("before"))
          val adapter = ProtobufStoreAdapter(
            store,
            schemaEntry().copy(generatedJvmClassName = "example.DifferentMessage")
          )

          val error = shouldThrow<IllegalArgumentException> { adapter.snapshot() }
          (error.message) shouldBe ("Proto value型がschema indexと一致しません。")
        }
      }
    }
  }

  private fun schemaEntry() = VerifiedSchemaEntry(
    generatedJvmClassName = StringValue::class.java.name,
    rootMessageFullName = "google.protobuf.StringValue",
    descriptorDigestSha256 = "a".repeat(64),
    descriptorAssetPath = "datastore-inspector/schemas/${"a".repeat(64)}.desc",
    descriptorBytes = byteArrayOf(1, 2, 3)
  )

  private class FakeDataStore(initial: MessageLite) : DataStore<MessageLite> {
    private val state = MutableStateFlow(initial)
    val value: MessageLite get() = state.value
    override val data: Flow<MessageLite> = state
    override suspend fun updateData(transform: suspend (t: MessageLite) -> MessageLite): MessageLite {
      val updated = transform(state.value)
      state.value = updated
      return updated
    }
  }
}
