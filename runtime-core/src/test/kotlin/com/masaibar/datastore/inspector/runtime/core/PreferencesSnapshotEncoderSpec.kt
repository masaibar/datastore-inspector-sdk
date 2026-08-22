package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.BooleanValue
import com.masaibar.datastore.inspector.protocol.CanonicalUtf8
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.StringSetValue
import com.masaibar.datastore.inspector.protocol.StringValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PreferencesSnapshotEncoderSpec : DescribeSpec() {
  init {
    describe("PreferencesSnapshotEncoder") {
      context("既存の契約を検証するとき") {
        it("unsigned UTF8順と型をfingerprintへ含めSet入力順には依存しない") {
          val values =
            linkedMapOf(
              "\u0080" to StringSetValue(listOf("😀", "a", "\u0080")),
              "a" to IntValue(1),
              "😀" to BooleanValue(true)
            )
          val reordered =
            linkedMapOf(
              "😀" to BooleanValue(true),
              "a" to IntValue(1),
              "\u0080" to StringSetValue(listOf("\u0080", "😀", "a"))
            )

          val first = PreferencesSnapshotEncoder.encode(values)
          val second = PreferencesSnapshotEncoder.encode(reordered)
          val root = (first.payload as PreferencesTree).root

          (root.children.map { it.name }) shouldBe (CanonicalUtf8.sorted(values.keys))
          ((root.children.single { it.name == "\u0080" }.value as StringSetValue).values) shouldBe (CanonicalUtf8.sorted(listOf("😀", "a", "\u0080")))
          (second.fingerprint) shouldBe (first.fingerprint)
          (PreferencesSnapshotEncoder.encode(values + ("a" to StringValue("1"))).fingerprint) shouldNotBe (first.fingerprint)
        }

        it("空keyと65535 byte超Stringを損失なくencodeする") {
          val longValue = "x".repeat(70_000)

          val snapshot =
            PreferencesSnapshotEncoder.encode(
              linkedMapOf(
                "" to StringValue(longValue),
                "   " to StringValue("blank")
              )
            )
          val root = (snapshot.payload as PreferencesTree).root

          (root.children.map { it.name }) shouldBe (listOf("", "   "))
          ((root.children.first().value as StringValue).value) shouldBe (longValue)
        }

        it("entry key String Setとaggregateの固定上限を超えると全体errorにする") {
          assertLimit {
            PreferencesSnapshotEncoder.encode(
              (0..PreferencesSnapshotLimits.MAX_ENTRIES).associate { index ->
                "key-$index" to IntValue(index)
              }
            )
          }
          assertLimit {
            PreferencesSnapshotEncoder.encode(
              mapOf(
                "k".repeat(ProtocolLimits.MAX_PREFERENCE_KEY_UTF8_BYTES + 1) to
                  StringValue("value")
              )
            )
          }
          assertLimit {
            PreferencesSnapshotEncoder.encode(
              mapOf(
                "value" to
                  StringValue(
                    "x".repeat(PreferencesSnapshotLimits.MAX_STRING_UTF8_BYTES + 1)
                  )
              )
            )
          }
          assertLimit {
            PreferencesSnapshotEncoder.encode(
              mapOf(
                "set" to
                  StringSetValue(
                    (0..PreferencesSnapshotLimits.MAX_SET_ELEMENTS)
                      .map(Int::toString)
                  )
              )
            )
          }
          assertLimit {
            PreferencesSnapshotEncoder.encode(
              (0..4).associate { setIndex ->
                "set-$setIndex" to
                  StringSetValue(
                    (0 until PreferencesSnapshotLimits.MAX_SET_ELEMENTS)
                      .map { element -> "$setIndex-$element" }
                  )
              }
            )
          }
          assertLimit {
            PreferencesSnapshotEncoder.encode(
              (0 until 5).associate { index ->
                "large-$index" to
                  StringValue("x".repeat(900_000))
              }
            )
          }
        }

        it("不正surrogateは置換せずstructured errorにする") {
          val malformed = "\ud800"

          val keyError =
            shouldThrow<StoreAdapterException> {
              PreferencesSnapshotEncoder.encode(mapOf(malformed to StringValue("value")))
            }
          val valueError =
            shouldThrow<StoreAdapterException> {
              PreferencesSnapshotEncoder.encode(mapOf("key" to StringValue(malformed)))
            }
          val setError =
            shouldThrow<StoreAdapterException> {
              PreferencesSnapshotEncoder.encode(
                mapOf("key" to StringSetValue(listOf(malformed)))
              )
            }

          (keyError.code) shouldBe (ProtocolErrorCode.STORE_UNSUPPORTED)
          (valueError.code) shouldBe (ProtocolErrorCode.STORE_UNSUPPORTED)
          (setError.code) shouldBe (ProtocolErrorCode.STORE_UNSUPPORTED)
        }

        it("境界内の最大StringとSetは部分結果なしでencodeできる") {
          val snapshot =
            PreferencesSnapshotEncoder.encode(
              mapOf(
                "large" to
                  StringValue(
                    "x".repeat(PreferencesSnapshotLimits.MAX_STRING_UTF8_BYTES)
                  ),
                "set" to
                  StringSetValue(
                    (0 until PreferencesSnapshotLimits.MAX_SET_ELEMENTS)
                      .map(Int::toString)
                  )
              )
            )

          ((snapshot.payload as PreferencesTree).root.children.isNotEmpty()) shouldBe true
        }
      }
    }
  }

  private fun assertLimit(block: () -> Unit) {
    val error = shouldThrow<StoreAdapterException>(block = block)
    (error.code) shouldBe (ProtocolErrorCode.PAYLOAD_TOO_LARGE)
  }
}
