@file:OptIn(InternalDataStoreInspectorProtocolApi::class)

package com.masaibar.datastore.inspector.sample

import com.masaibar.datastore.inspector.protocol.InternalDataStoreInspectorProtocolApi
import com.masaibar.datastore.inspector.protocol.ProtoRaw
import com.masaibar.datastore.inspector.protocol.ProtoSchemaRef
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ReplaceProtoBytes
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.ResolvedStoreSnapshot
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.SnapshotResultResponse
import com.masaibar.datastore.inspector.protocol.WriteRequest
import com.masaibar.datastore.inspector.sample.proto.UserSettings
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension

class ProtoRuntimeContractFixtureSpec : DescribeSpec() {
  init {
    describe("ProtoRuntimeContractFixture") {
      context("既存の契約を検証するとき") {
        it("Runtime側の実UserSettingsとschemaからclient向けProto snapshotを生成する") {
          val descriptorBytes = Files.readAllBytes(schemaFixture())
          val schemaOutput = contractPath("datastore.inspector.proto.schema.output")
          val responseOutput = contractPath("datastore.inspector.proto.contract.output")
          Files.createDirectories(schemaOutput.parent)
          Files.createDirectories(responseOutput.parent)
          Files.write(schemaOutput, descriptorBytes)

          val schema =
            ProtoSchemaRef(
              schemaId = sha256(descriptorBytes),
              rootMessageFullName = ROOT_MESSAGE,
              descriptorDigestSha256 = sha256(descriptorBytes)
            )
          val valueBytes = runtimeMessage().toByteArray() + UNKNOWN_FIELD
          val expected =
            ResponseEnvelope(
              requestId = "runtime-proto-snapshot-1",
              payload =
                SnapshotResultResponse(
                  ResolvedSnapshotResult(
                    ResolvedStoreSnapshot(
                      storeId = "proto-user-settings",
                      revision = 11,
                      contentToken = "runtime-proto-token",
                      payload = ProtoRaw(schema, valueBytes)
                    )
                  )
                )
            )
          Files.write(responseOutput, ProtocolJson.encodeResponse(expected))

          (ProtocolJson.decodeResponse(Files.readAllBytes(responseOutput))) shouldBe (expected)
          (UserSettings.parseFrom(valueBytes).hasNickname()) shouldBe true
          (valueBytes.containsSequence(UNKNOWN_FIELD)) shouldBe true
        }

        it("clientが編集した全数値型collectionとpresenceを実UserSettingsで復元する") {
          val inputProperty = System.getProperty("datastore.inspector.proto.contract.input") ?: return@it
          val input = Path.of(inputProperty)
          withClue("Proto contract fixtureが存在しません。") { (Files.isRegularFile(input)) shouldBe true }
          val envelope: RequestEnvelope = ProtocolJson.decodeRequest(Files.readAllBytes(input))
          (envelope.requestId) shouldBe ("client-proto-write-1")
          val request = (envelope.payload).shouldBeInstanceOf<WriteRequest>()
          (request.write.storeId) shouldBe ("proto-user-settings")
          (request.write.expectedRevision) shouldBe (11)
          (request.write.expectedContentToken) shouldBe ("runtime-proto-token")
          val operation = (request.write.operation).shouldBeInstanceOf<ReplaceProtoBytes>()
          val settings = UserSettings.parseFrom(operation.valueBytes)

          (settings.launchCount) shouldBe (Int.MAX_VALUE)
          (settings.lastLoginEpochMillis) shouldBe (Long.MAX_VALUE)
          (settings.rating.isNaN()) shouldBe true
          (settings.balance) shouldBe (Double.NEGATIVE_INFINITY)
          (settings.uint32Value) shouldBe (UInt.MAX_VALUE.toInt())
          (settings.uint64Value) shouldBe (ULong.MAX_VALUE.toLong())
          (settings.sint32Value) shouldBe (Int.MIN_VALUE)
          (settings.sint64Value) shouldBe (Long.MIN_VALUE)
          (settings.fixed32Value) shouldBe (UInt.MAX_VALUE.toInt())
          (settings.fixed64Value) shouldBe (ULong.MAX_VALUE.toLong())
          (settings.sfixed32Value) shouldBe (Int.MAX_VALUE)
          (settings.sfixed64Value) shouldBe (Long.MAX_VALUE)

          (settings.unsignedIdsList) shouldBe (listOf(ULong.MAX_VALUE.toLong(), 0L, Long.MIN_VALUE))
          (settings.notificationProfilesCount) shouldBe (3)
          (settings.getNotificationProfiles(0).channelsList.single()) shouldBe ("second")
          (settings.getNotificationProfiles(1).channelsList.single()) shouldBe ("first")
          (settings.getNotificationProfiles(2).channelsList.single()) shouldBe ("third")
          (settings.getNotificationProfiles(0).enabled) shouldBe true

          (settings.unsignedCountersMap.getValue(UInt.MAX_VALUE.toInt())) shouldBe (ULong.MAX_VALUE.toLong())
          (settings.unsignedCountersMap.getValue(0)) shouldBe (0L)
          (settings.notificationRoutesMap.keys) shouldBe (setOf("renamed", "new"))
          (settings.notificationRoutesMap.getValue("renamed").channelsList.single()) shouldBe ("push")
          (settings.notificationRoutesMap.getValue("new").enabled) shouldBe true
          (settings.notificationRoutesMap.getValue("new").channelsList.single()) shouldBe ("route")

          (settings.hasNickname()) shouldBe true
          (settings.nickname) shouldBe ("roundtrip")
          (settings.destinationCase) shouldBe (UserSettings.DestinationCase.CONTENT_ID)
          (settings.contentId) shouldBe (Long.MAX_VALUE)
          (operation.valueBytes.containsSequence(UNKNOWN_FIELD)) shouldBe true
        }
      }
    }
  }

  private fun runtimeMessage(): UserSettings =
    UserSettings.newBuilder()
      .setUserName("runtime-user")
      .setLaunchCount(Int.MIN_VALUE)
      .setLastLoginEpochMillis(Long.MIN_VALUE)
      .setRating(Float.POSITIVE_INFINITY)
      .setBalance(Double.NaN)
      .setUint32Value(UInt.MAX_VALUE.toInt())
      .setUint64Value(ULong.MAX_VALUE.toLong())
      .setSint32Value(Int.MIN_VALUE)
      .setSint64Value(Long.MIN_VALUE)
      .setFixed32Value(UInt.MAX_VALUE.toInt())
      .setFixed64Value(ULong.MAX_VALUE.toLong())
      .setSfixed32Value(Int.MIN_VALUE)
      .setSfixed64Value(Long.MIN_VALUE)
      .setNickname("")
      .setScreenName("runtime-screen")
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
      .putUnsignedCounters(1, 2)
      .putNotificationRoutes(
        "primary",
        UserSettings.NotificationSettings.newBuilder()
          .setEnabled(true)
          .addChannels("push")
          .build()
      )
      .putNotificationRoutes(
        "secondary",
        UserSettings.NotificationSettings.newBuilder()
          .setEnabled(false)
          .addChannels("email")
          .build()
      )
      .build()

  private fun schemaFixture(): Path {
    val root = contractPath("datastore.inspector.proto.schema.fixture")
    val descriptors =
      Files.walk(root).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) && path.extension == "desc" }
          .toList()
      }
    return descriptors.singleOrNull()
      ?: error("Proto descriptor fixtureは1個必要です: $descriptors")
  }

  private fun contractPath(property: String): Path =
    Path.of(checkNotNull(System.getProperty(property)) { "$property が設定されていません。" })

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
    indices.any { start ->
      start + sequence.size <= size &&
        sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
    }

  private companion object {
    const val ROOT_MESSAGE = "datastore.inspector.sample.UserSettings"
    val UNKNOWN_FIELD = byteArrayOf(0x98.toByte(), 0x06, 0x07)
  }
}
