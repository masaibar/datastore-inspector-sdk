package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.CustomDocumentFormat
import com.masaibar.datastore.inspector.protocol.CustomDocumentPayload
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.StoreStatus
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import com.masaibar.datastore.inspector.protocol.UnsupportedSnapshotInfo
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import com.masaibar.datastore.inspector.protocol.WriteOperation
import com.masaibar.datastore.inspector.protocol.WritePayload
import com.masaibar.datastore.inspector.protocol.WriteSuccess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RuntimeCustomCapabilitySpec :
  DescribeSpec({
    describe("static Custom Store capability gate") {
      context("1.0/1.1相当clientがCustom capabilityを広告しないとき") {
        lateinit var harness: CapabilityHarness
        val contexts =
          listOf(
            ProtocolVersion(1, 0),
            ProtocolVersion(1, 1)
          ).map { version ->
            connectionContext(
              version = version,
              capabilities =
                setOf(
                  ProtocolCapabilities.STORES_LIST,
                  ProtocolCapabilities.SNAPSHOT_GET
                )
            )
          }
        val expectedReason =
          UnsupportedReason(
            CustomStoreReasonCode.UNKNOWN,
            "Custom DataStoreはこのProtocol接続では利用できません。",
            false
          )
        beforeEach { harness = CapabilityHarness() }
        afterEach { harness.close() }

        it("listとsnapshotをfixed Unsupportedへ縮退しwriteを拒否してCustom payloadを生成しない") {
          contexts.forEach { context ->
            val descriptor = harness.service.list(context).single()
            val snapshot =
              harness.service
                .snapshot(harness.entry.storeId, context)
                .shouldBeInstanceOf<UnsupportedSnapshotInfo>()
            val writeFailure =
              shouldThrow<RuntimeStoreException.Unsupported> {
                harness.service.write(harness.writePayload(), context)
              }

            descriptor.id shouldBe harness.entry.storeId
            descriptor.kind shouldBe StoreKind.CUSTOM
            descriptor.status shouldBe StoreStatus.UNSUPPORTED
            descriptor.capabilities.shouldBeEmpty()
            descriptor.schema shouldBe null
            descriptor.unsupportedReason shouldBe expectedReason
            descriptor.semantics shouldBe defaultStoreSemantics(StoreKind.CUSTOM)
            snapshot.storeId shouldBe harness.entry.storeId
            snapshot.reason shouldBe expectedReason
            writeFailure.message shouldBe "Storeは未対応です。"
          }

          harness.adapter.snapshotCalls.get() shouldBe 0
          harness.adapter.writeCalls.get() shouldBe 0
        }
      }

      context("clientがCustom GETだけを広告するとき") {
        lateinit var harness: CapabilityHarness
        val context =
          connectionContext(
            capabilities =
              setOf(
                ProtocolCapabilities.STORES_LIST,
                ProtocolCapabilities.SNAPSHOT_GET,
                ProtocolCapabilities.CUSTOM_DOCUMENT_GET
              )
          )

        beforeEach { harness = CapabilityHarness() }
        afterEach { harness.close() }

        it("listとsnapshotを許可しreplaceだけをCapability errorにする") {
          val descriptor = harness.service.list(context).single()
          val snapshot =
            harness.service
              .snapshot(harness.entry.storeId, context)
              .shouldBeInstanceOf<ResolvedSnapshotResult>()
              .snapshot
          val writeFailure =
            shouldThrow<RuntimeStoreException.Capability> {
              harness.service.write(
                harness.writePayload(
                  revision = snapshot.revision,
                  token = snapshot.contentToken
                ),
                context
              )
            }

          descriptor.capabilities.map(StoreCapability::id).toSet() shouldContainExactly
            setOf(
              ProtocolCapabilities.SNAPSHOT_GET,
              ProtocolCapabilities.CUSTOM_DOCUMENT_GET
            )
          snapshot.payload.shouldBeInstanceOf<CustomDocumentPayload>().document shouldBe
            """{"counter":42}"""
          writeFailure.message shouldBe "交渉されていないcapabilityです。"
          harness.adapter.writeCalls.get() shouldBe 0
        }
      }

      context("clientがCustom GETとREPLACEを広告するとき") {
        lateinit var harness: CapabilityHarness
        val context =
          connectionContext(
            capabilities =
              setOf(
                ProtocolCapabilities.STORES_LIST,
                ProtocolCapabilities.SNAPSHOT_GET,
                ProtocolCapabilities.CUSTOM_DOCUMENT_GET,
                ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE
              )
          )

        beforeEach { harness = CapabilityHarness() }
        afterEach { harness.close() }

        it("snapshot leaseを1回だけ消費してreplaceを適用する") {
          val snapshot =
            harness.service
              .snapshot(harness.entry.storeId, context)
              .shouldBeInstanceOf<ResolvedSnapshotResult>()
              .snapshot
          val result =
            harness.service.write(
              harness.writePayload(
                revision = snapshot.revision,
                token = snapshot.contentToken
              ),
              context
            )

          result
            .shouldBeInstanceOf<WriteSuccess>()
            .snapshot.payload
            .shouldBeInstanceOf<CustomDocumentPayload>()
            .document shouldBe """{"counter":43}"""
          harness.adapter.writeCalls.get() shouldBe 1
        }
      }

      context("projection failureでCustom adapterがquarantineされたとき") {
        lateinit var harness: CapabilityHarness
        val context =
          connectionContext(
            capabilities =
              setOf(
                ProtocolCapabilities.STORES_LIST,
                ProtocolCapabilities.SNAPSHOT_GET,
                ProtocolCapabilities.CUSTOM_DOCUMENT_GET,
                ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE
              )
          )
        val reason =
          UnsupportedReason(
            CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH,
            "Custom DataStoreを安全に投影できません。",
            false
          )

        beforeEach {
          harness = CapabilityHarness()
          harness.adapter.unsupported.set(reason)
        }
        afterEach { harness.close() }

        it("listをUnsupported/capability空にしsnapshotと再送を拒否する") {
          val descriptor = harness.service.list(context).single()
          val snapshot =
            harness.service
              .snapshot(harness.entry.storeId, context)
              .shouldBeInstanceOf<UnsupportedSnapshotInfo>()
          val writeFailure =
            shouldThrow<RuntimeStoreException.Unsupported> {
              harness.service.write(harness.writePayload(), context)
            }

          descriptor.status shouldBe StoreStatus.UNSUPPORTED
          descriptor.capabilities.shouldBeEmpty()
          descriptor.unsupportedReason shouldBe reason
          snapshot.reason shouldBe reason
          writeFailure.message shouldBe "Storeは未対応です。"
          harness.adapter.writeCalls.get() shouldBe 0
        }
      }
    }
  })

private class CapabilityHarness : AutoCloseable {
  val adapter = CapabilityCustomAdapter()
  private val registry = DataStoreRegistry { "custom-store" }
  val entry =
    registry.resolve(
      instance = Any(),
      declaration =
        StoreDeclaration(
          declarationId = "custom-declaration",
          name = "custom",
          fileName = "custom.pb",
          kindHint = StoreKind.CUSTOM,
          owner = "fixture.CustomStores",
          property = "custom"
        ),
      factories =
        listOf(
          object : StoreAdapterFactory {
            override val providerId: String = "capability-custom"

            override fun create(candidate: StoreCandidate): AdapterResolution = AdapterResolution.Resolved(adapter)
          }
        )
    )
  val service = RuntimeStoreService(registry)

  fun writePayload(
    revision: Long = 1,
    token: String = "unused-token"
  ): WritePayload =
    WritePayload(
      storeId = entry.storeId,
      expectedRevision = revision,
      expectedContentToken = token,
      operation =
        ReplaceCustomDocument(
          projectionId = DIRECT_JSON_PROJECTION_ID,
          schemaVersion = 1,
          format = CustomDocumentFormat.JSON,
          document = """{"counter":43}"""
        )
    )

  override fun close() {
    service.close()
    registry.clear()
  }
}

private class CapabilityCustomAdapter : StoreAdapter {
  val snapshotCalls = AtomicInteger()
  val writeCalls = AtomicInteger()
  val unsupported = AtomicReference<UnsupportedReason?>(null)
  override val kind: StoreKind = StoreKind.CUSTOM
  override val capabilities: Set<StoreCapability> =
    setOf(
      StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
      StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_GET),
      StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
    )
  override val schema = null
  override val semantics: StoreSemantics =
    StoreSemantics(
      backend = StoreBackend.DATASTORE,
      storageScope = StorageScope.CREDENTIAL_PROTECTED,
      supportedValueTypes = emptySet(),
      writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
    )
  override val requiredCapabilities: Set<String> =
    setOf(ProtocolCapabilities.CUSTOM_DOCUMENT_GET)
  override val runtimeUnsupportedReason: UnsupportedReason?
    get() = unsupported.get()

  override suspend fun snapshot(): AdapterSnapshot {
    snapshotCalls.incrementAndGet()
    unsupported.get()?.let { reason ->
      throw StoreSnapshotUnsupportedException(reason)
    }
    return AdapterSnapshot(
      fingerprint = "custom-fingerprint",
      payload =
        CustomDocumentPayload(
          projectionId = DIRECT_JSON_PROJECTION_ID,
          schemaVersion = 1,
          format = CustomDocumentFormat.JSON,
          document = """{"counter":42}"""
        )
    )
  }

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult {
    writeCalls.incrementAndGet()
    val replace = operation.shouldBeInstanceOf<ReplaceCustomDocument>()
    return AdapterWriteResult.Applied(
      AdapterSnapshot(
        fingerprint = "updated-custom-fingerprint",
        payload =
          CustomDocumentPayload(
            projectionId = replace.projectionId,
            schemaVersion = replace.schemaVersion,
            format = replace.format,
            document = replace.document
          )
      )
    )
  }
}

private fun connectionContext(
  version: ProtocolVersion = ProtocolVersion.CURRENT,
  capabilities: Set<String>
): RuntimeConnectionContext =
  RuntimeConnectionContext(
    version = version,
    capabilities = capabilities,
    sessionId = "custom-capability-test"
  )
