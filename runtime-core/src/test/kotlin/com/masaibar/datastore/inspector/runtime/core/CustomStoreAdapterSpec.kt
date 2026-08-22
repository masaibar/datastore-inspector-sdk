package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import com.masaibar.datastore.inspector.protocol.CustomDocumentFormat
import com.masaibar.datastore.inspector.protocol.CustomDocumentPayload
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreStatus
import com.masaibar.datastore.inspector.protocol.UnsupportedSnapshotInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class CustomStoreAdapterSpec :
  DescribeSpec({
    describe("official DataStore 1.2.1 creation routes") {
      OfficialRoute.entries.forEach { route ->
        context("${route.displayName}でCustom Storeを作成したとき") {
          lateinit var harness: OfficialStoreHarness
          lateinit var replacement: ReplaceCustomDocument

          beforeEach {
            harness = OfficialStoreHarness.create(route)
            replacement =
              ReplaceCustomDocument(
                projectionId = DIRECT_JSON_PROJECTION_ID,
                schemaVersion = 1,
                format = CustomDocumentFormat.JSON,
                document = """{"label":"edited","counter":2}"""
              )
          }

          afterEach {
            harness.close()
          }

          it("同一DataStoreのactual writeToまでtokenとcandidate identityを維持して更新する") {
            val initial = harness.adapter.snapshot()
            val result = harness.adapter.write(initial.fingerprint, replacement)
            val current = runBlocking { harness.store.data.first() }
            val payload =
              result
                .shouldBeInstanceOf<AdapterWriteResult.Applied>()
                .snapshot.payload
                .shouldBeInstanceOf<CustomDocumentPayload>()
            val declaration =
              DataStoreInspectorRuntime
                .registry()
                .entries()
                .single {
                  it.declaration.declarationId == harness.declarationId
                }.declaration

            current shouldBe Settings("edited", 2)
            payload.document shouldBe replacement.document
            harness.producerCalls.get() shouldBe 1
            declaration.fileName shouldBe harness.file.name
            declaration.name shouldBe harness.file.name
          }
        }
      }
    }

    describe("Custom write safety") {
      context("replace documentがcurrent snapshotと同一のとき") {
        lateinit var harness: OfficialStoreHarness

        beforeEach {
          harness = OfficialStoreHarness.create(OfficialRoute.FILE_STORAGE)
        }

        afterEach {
          harness.close()
        }

        it("同じsnapshotをAppliedとして返しactual storageへ書き込まない") {
          val initial = harness.adapter.snapshot()
          val payload =
            initial.payload.shouldBeInstanceOf<CustomDocumentPayload>()
          val result =
            harness.adapter.write(
              initial.fingerprint,
              ReplaceCustomDocument(
                projectionId = payload.projectionId,
                schemaVersion = payload.schemaVersion,
                format = payload.format,
                document = payload.document
              )
            )
          val applied =
            result.shouldBeInstanceOf<AdapterWriteResult.Applied>().snapshot
          val current = runBlocking { harness.store.data.first() }

          applied shouldBe initial
          current shouldBe Settings("before", 1)
          harness.file.exists() shouldBe false
        }
      }

      context("actual serializerの3回目のcandidate出力だけが別valueへ戻るとき") {
        lateinit var harness: OfficialStoreHarness
        lateinit var replacement: ReplaceCustomDocument

        beforeEach {
          harness =
            OfficialStoreHarness.create(
              route = OfficialRoute.FILE_STORAGE,
              serializer = ActualMismatchSerializer()
            )
          replacement =
            ReplaceCustomDocument(
              projectionId = DIRECT_JSON_PROJECTION_ID,
              schemaVersion = 1,
              format = CustomDocumentFormat.JSON,
              document = """{"label":"edited","counter":2}"""
            )
        }

        afterEach {
          harness.close()
        }

        it("scratch commit前にabortしてcacheとfileを維持しStoreをquarantineする") {
          val initial = harness.adapter.snapshot()
          val failure =
            shouldThrow<StoreAdapterException> {
              harness.adapter.write(initial.fingerprint, replacement)
            }
          val current = runBlocking { harness.store.data.first() }
          val descriptors = RuntimeStoreService(DataStoreInspectorRuntime.registry()).list()
          val descriptor =
            descriptors.single { it.id == harness.entry.storeId }
          val unsupported =
            runBlocking {
              RuntimeStoreService(DataStoreInspectorRuntime.registry())
                .snapshot(harness.entry.storeId)
            }

          failure.code shouldBe ProtocolErrorCode.CUSTOM_ACTUAL_WRITE_MISMATCH
          failure.operationStarted shouldBe false
          current shouldBe Settings("before", 1)
          harness.file.exists() shouldBe false
          descriptor.status shouldBe StoreStatus.UNSUPPORTED
          descriptor.capabilities.shouldBeEmpty()
          descriptor.unsupportedReason?.code shouldBe
            CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH.wireName
          unsupported.shouldBeInstanceOf<UnsupportedSnapshotInfo>().reason.code shouldBe
            CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH.wireName
        }
      }

      ActualPostconditionThrowMode.entries.forEach { mode ->
        context("actual outputの${mode.displayName}が例外を投げるとき") {
          lateinit var harness: OfficialStoreHarness
          lateinit var replacement: ReplaceCustomDocument

          beforeEach {
            harness =
              OfficialStoreHarness.create(
                route = OfficialRoute.FILE_STORAGE,
                serializer = ActualPostconditionThrowSerializer(mode)
              )
            replacement =
              ReplaceCustomDocument(
                projectionId = DIRECT_JSON_PROJECTION_ID,
                schemaVersion = 1,
                format = CustomDocumentFormat.JSON,
                document = """{"label":"edited","counter":2}"""
              )
          }

          afterEach {
            harness.close()
          }

          it("scratchをcommitせずactual write mismatchへ正規化してStoreを隔離する") {
            val initial = harness.adapter.snapshot()
            val failure =
              shouldThrow<StoreAdapterException> {
                harness.adapter.write(initial.fingerprint, replacement)
              }
            val current = runBlocking { harness.store.data.first() }
            val descriptor =
              RuntimeStoreService(DataStoreInspectorRuntime.registry())
                .list()
                .single { it.id == harness.entry.storeId }

            failure.code shouldBe ProtocolErrorCode.CUSTOM_ACTUAL_WRITE_MISMATCH
            failure.operationStarted shouldBe false
            current shouldBe Settings("before", 1)
            harness.file.exists() shouldBe false
            descriptor.status shouldBe StoreStatus.UNSUPPORTED
            descriptor.unsupportedReason?.code shouldBe
              CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH.wireName
          }
        }
      }

      ActualControlFlowThrowMode.entries.forEach { mode ->
        context("actual outputのdecodeが${mode.displayName}を投げるとき") {
          lateinit var harness: OfficialStoreHarness
          lateinit var thrown: Throwable

          beforeEach {
            thrown = mode.createThrowable()
            harness =
              OfficialStoreHarness.create(
                route = OfficialRoute.FILE_STORAGE,
                serializer = ActualControlFlowThrowSerializer(thrown)
              )
          }

          afterEach {
            harness.close()
          }

          it("scratchをcommitせず例外を再throwして開始済みStoreを安全側へ隔離する") {
            val initial = harness.adapter.snapshot()
            val caught =
              shouldThrow<Throwable> {
                harness.adapter.write(
                  initial.fingerprint,
                  ReplaceCustomDocument(
                    projectionId = DIRECT_JSON_PROJECTION_ID,
                    schemaVersion = 1,
                    format = CustomDocumentFormat.JSON,
                    document = """{"label":"edited","counter":2}"""
                  )
                )
              }
            val current = runBlocking { harness.store.data.first() }
            val descriptor =
              RuntimeStoreService(DataStoreInspectorRuntime.registry())
                .list()
                .single { it.id == harness.entry.storeId }

            (caught === thrown) shouldBe true
            current shouldBe Settings("before", 1)
            harness.file.exists() shouldBe false
            descriptor.status shouldBe StoreStatus.UNSUPPORTED
            descriptor.unsupportedReason?.code shouldBe mode.reason.wireName
          }
        }

        context("actual outputのdecodeがcause chain内に${mode.displayName}を含むとき") {
          lateinit var harness: OfficialStoreHarness
          lateinit var original: Throwable

          beforeEach {
            original = mode.createThrowable()
            harness =
              OfficialStoreHarness.create(
                route = OfficialRoute.FILE_STORAGE,
                serializer =
                  ActualControlFlowThrowSerializer(
                    IOException(
                      "actual control-flow wrapper",
                      original
                    )
                  )
              )
          }

          afterEach {
            harness.close()
          }

          it("通常のactual write mismatchへ変換せず最深例外を同一identityで再throwする") {
            val initial = harness.adapter.snapshot()
            val caught =
              shouldThrow<Throwable> {
                harness.adapter.write(
                  initial.fingerprint,
                  ReplaceCustomDocument(
                    projectionId = DIRECT_JSON_PROJECTION_ID,
                    schemaVersion = 1,
                    format = CustomDocumentFormat.JSON,
                    document = """{"label":"edited","counter":2}"""
                  )
                )
              }
            val current = runBlocking { harness.store.data.first() }

            (caught === original) shouldBe true
            current shouldBe Settings("before", 1)
            harness.file.exists() shouldBe false
          }
        }
      }

      CandidateControlFlowPhase.entries.forEach { phase ->
        ActualControlFlowThrowMode.entries.forEach { mode ->
          context("candidate ${phase.displayName}がcause chain内に${mode.displayName}を含むとき") {
            lateinit var harness: OfficialStoreHarness
            lateinit var original: Throwable

            beforeEach {
              original = mode.createThrowable()
              harness =
                OfficialStoreHarness.create(
                  route = OfficialRoute.FILE_STORAGE,
                  serializer =
                    CandidateControlFlowSerializer(
                      phase = phase,
                      failure =
                        IOException(
                          "candidate control-flow wrapper",
                          original
                        )
                    )
                )
            }

            afterEach {
              harness.close()
            }

            it("通常のprojection mismatchへ変換せず最深例外を同一identityで再throwする") {
              val initial = harness.adapter.snapshot()
              val caught =
                shouldThrow<Throwable> {
                  harness.adapter.write(
                    initial.fingerprint,
                    ReplaceCustomDocument(
                      projectionId = DIRECT_JSON_PROJECTION_ID,
                      schemaVersion = 1,
                      format = CustomDocumentFormat.JSON,
                      document = """{"label":"edited","counter":2}"""
                    )
                  )
                }
              val current = runBlocking { harness.store.data.first() }

              (caught === original) shouldBe true
              current shouldBe Settings("before", 1)
              harness.file.exists() shouldBe false
            }
          }
        }
      }

      context("外部更新でsnapshot fingerprintがstaleになったとき") {
        lateinit var harness: OfficialStoreHarness
        lateinit var replacement: ReplaceCustomDocument

        beforeEach {
          harness = OfficialStoreHarness.create(OfficialRoute.FILE_STORAGE)
          replacement =
            ReplaceCustomDocument(
              projectionId = DIRECT_JSON_PROJECTION_ID,
              schemaVersion = 1,
              format = CustomDocumentFormat.JSON,
              document = """{"label":"inspector","counter":3}"""
            )
        }

        afterEach {
          harness.close()
        }

        it("同じupdateData transaction内のcurrentを比較してConflictを返す") {
          val initial = harness.adapter.snapshot()
          runBlocking {
            harness.store.updateData { Settings("external", 2) }
          }
          val result =
            runBlocking {
              harness.adapter.write(initial.fingerprint, replacement)
            }
          val current = runBlocking { harness.store.data.first() }
          val payload =
            result
              .shouldBeInstanceOf<AdapterWriteResult.Conflict>()
              .snapshot.payload
              .shouldBeInstanceOf<CustomDocumentPayload>()

          current shouldBe Settings("external", 2)
          payload.document shouldBe """{"label":"external","counter":2}"""
        }
      }

      context("変更documentをdecodeしたcandidateのequalsが差分を無視するとき") {
        lateinit var harness: CoarseEqualityHarness
        lateinit var replacement: ReplaceCustomDocument

        beforeEach {
          harness = CoarseEqualityHarness.create()
          replacement =
            ReplaceCustomDocument(
              projectionId = DIRECT_JSON_PROJECTION_ID,
              schemaVersion = 1,
              format = CustomDocumentFormat.JSON,
              document = """{"label":"same","hidden":"edited"}"""
            )
        }

        afterEach {
          harness.close()
        }

        it("DataStoreがwriteをskipする前にequality-too-coarseとして拒否する") {
          val initial = harness.adapter.snapshot()
          val failure =
            shouldThrow<StoreAdapterException> {
              harness.adapter.write(initial.fingerprint, replacement)
            }
          val current = runBlocking { harness.store.data.first() }

          failure.code shouldBe ProtocolErrorCode.CUSTOM_PROJECTION_MISMATCH
          failure.operationStarted shouldBe false
          current.hidden shouldBe "original"
          harness.file.exists() shouldBe false
          CoarseSerializer.editedWriteCalls.get() shouldBe 0
        }
      }
    }

    describe("fallback codec") {
      context("binary Serializerに一致するcodec providerがないとき") {
        lateinit var harness: BinaryStoreHarness

        beforeEach {
          InspectorCustomCodecRegistry.clear()
          harness = BinaryStoreHarness.create()
        }

        afterEach {
          harness.close()
          InspectorCustomCodecRegistry.clear()
        }

        it("raw bytesを公開せず理由付きUnsupportedへfail closedする") {
          val failure =
            shouldThrow<StoreSnapshotUnsupportedException> {
              harness.adapter.snapshot()
            }

          failure.reason.code shouldBe
            CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH.wireName
          harness.adapter.runtimeUnsupportedReason?.code shouldBe failure.reason.code
        }
      }

      context("serializer classとvalue classがexact matchするcodecが1件あるとき") {
        lateinit var harness: BinaryStoreHarness
        lateinit var replacement: ReplaceCustomDocument

        beforeEach {
          harness = BinaryStoreHarness.create()
          InspectorCustomCodecRegistry.replaceForTest(
            listOf(BinaryCodecProvider)
          )
          replacement =
            ReplaceCustomDocument(
              projectionId = "fallback:settings:1",
              schemaVersion = 1,
              format = CustomDocumentFormat.JSON,
              document = """{"label":"codec-edited","counter":9}"""
            )
        }

        afterEach {
          harness.close()
          InspectorCustomCodecRegistry.clear()
        }

        it("同一DataStoreとoriginal serializerのpostconditionを通して全fieldを更新する") {
          val initial = harness.adapter.snapshot()
          val initialPayload =
            initial.payload.shouldBeInstanceOf<CustomDocumentPayload>()
          val result = harness.adapter.write(initial.fingerprint, replacement)
          val current = runBlocking { harness.store.data.first() }
          val resultPayload =
            result
              .shouldBeInstanceOf<AdapterWriteResult.Applied>()
              .snapshot.payload
              .shouldBeInstanceOf<CustomDocumentPayload>()

          initialPayload.projectionId shouldBe "fallback:settings:1"
          current shouldBe Settings("codec-edited", 9)
          resultPayload.document shouldBe replacement.document
        }
      }

      context("codec IDが非ASCIIまたはprovider/bindingが重複するとき") {
        val invalidIdProvider =
          codecProvider(
            providerId = "invalid-id",
            codec = SettingsCodec("機微-class-name")
          )
        val duplicateProviders =
          listOf(
            codecProvider("duplicate", SettingsCodec("one")),
            codecProvider("duplicate", SettingsCodec("two"))
          )
        val duplicateBindings =
          listOf(
            object : InspectorCustomCodecBindingProvider {
              override val providerId: String = "duplicate-bindings"

              override fun bindings(): List<InspectorCustomCodecBinding<*>> =
                listOf(
                  codecBinding(SettingsCodec("one")),
                  codecBinding(SettingsCodec("two"))
                )
            }
          )

        it("いずれも一意なbindingとして解決しない") {
          InspectorCustomCodecRegistry.replaceForTest(listOf(invalidIdProvider))
          InspectorCustomCodecRegistry.resolve(
            BinarySettingsSerializer::class.java,
            Settings::class.java
          ) shouldBe CodecResolution.Ambiguous

          InspectorCustomCodecRegistry.replaceForTest(duplicateProviders)
          InspectorCustomCodecRegistry.resolve(
            BinarySettingsSerializer::class.java,
            Settings::class.java
          ) shouldBe CodecResolution.Ambiguous

          InspectorCustomCodecRegistry.replaceForTest(duplicateBindings)
          InspectorCustomCodecRegistry.resolve(
            BinarySettingsSerializer::class.java,
            Settings::class.java
          ) shouldBe CodecResolution.Ambiguous
          InspectorCustomCodecRegistry.clear()
        }
      }
    }

    describe("projection cache") {
      context("同じgenerated projectionを同じruntime classへ再利用するとき") {
        lateinit var serializer: CountingGeneratedSerializer
        lateinit var handle: CustomInspectionHandle<CacheSettings>
        lateinit var resolver: CustomProjectionResolver<CacheSettings>
        val current = CacheSettings("cache", 1)

        beforeEach {
          serializer = CountingGeneratedSerializer()
          handle =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          resolver = CustomProjectionResolver(handle)
        }

        afterEach {
          handle.close()
        }

        it("2回目は候補探索を省略し同じcurrentのpersistence preflightは1回通す") {
          resolver.resolve(current)
          val afterFirst = serializer.writeCalls.get()
          val readsAfterFirst = serializer.readCalls.get()
          resolver.resolve(current)
          val secondDelta = serializer.writeCalls.get() - afterFirst
          val secondReadDelta = serializer.readCalls.get() - readsAfterFirst

          afterFirst shouldBe 2
          secondDelta shouldBe 1
          secondReadDelta shouldBe 1
        }
      }

      context("同じruntime classでも値Bだけoriginal persistenceがlossyなとき") {
        lateinit var serializer: ValueDependentLossySerializer
        lateinit var handle: CustomInspectionHandle<CacheSettings>
        lateinit var resolver: CustomProjectionResolver<CacheSettings>

        beforeEach {
          serializer = ValueDependentLossySerializer()
          handle =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          resolver = CustomProjectionResolver(handle)
        }

        afterEach {
          handle.close()
        }

        it("値Aのgenerated cacheを信頼せず値Bのpreflightでfail closedする") {
          val safe = resolver.resolve(CacheSettings("safe", 1))
          val readsBeforeLossy = serializer.readCalls.get()
          val failure =
            shouldThrow<CustomInspectionFailure> {
              resolver.resolve(CacheSettings("lossy", 2))
            }

          safe.projection.projectionId shouldBe GENERATED_PROJECTION_ID
          (serializer.readCalls.get() > readsBeforeLossy) shouldBe true
          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE
        }
      }

      context("binary outputのfallback codec projectionを同じclassへ再利用するとき") {
        lateinit var serializer: FallbackCacheSerializer
        lateinit var provider: FallbackCacheProvider
        lateinit var handle: CustomInspectionHandle<FallbackCacheValue>
        lateinit var resolver: CustomProjectionResolver<FallbackCacheValue>
        val current = FallbackCacheValue("cache", 7)

        beforeEach {
          serializer = FallbackCacheSerializer()
          provider = FallbackCacheProvider(serializer.javaClass)
          InspectorCustomCodecRegistry.replaceForTest(listOf(provider))
          handle =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          resolver = CustomProjectionResolver(handle)
        }

        afterEach {
          handle.close()
          InspectorCustomCodecRegistry.clear()
        }

        it("binaryのままならbinding候補を再探索せず値ごとのpreflightだけを通す") {
          val first = resolver.resolve(current)
          val writesAfterFirst = serializer.writeCalls.get()
          val readsAfterFirst = serializer.readCalls.get()
          val second = resolver.resolve(current)

          first.projection.projectionId shouldBe "fallback:cache-value:1"
          second.projection.projectionId shouldBe first.projection.projectionId
          provider.bindingsCalls.get() shouldBe 1
          serializer.writeCalls.get() - writesAfterFirst shouldBe 1
          serializer.readCalls.get() - readsAfterFirst shouldBe 1
        }
      }

      context("同じruntime classのactual outputがtextからJSONへ変わるとき") {
        lateinit var handle: CustomInspectionHandle<RouteValue>
        lateinit var resolver: CustomProjectionResolver<RouteValue>

        beforeEach {
          handle =
            CustomInspectionHandle
              .forSerializer(
                RouteChangingSerializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          resolver = CustomProjectionResolver(handle)
        }

        afterEach {
          handle.close()
        }

        it("cached textを捨てて優先度の高いdirect JSONへfull resolveする") {
          val text = resolver.resolve(RouteValue(json = false, value = "first"))
          val json = resolver.resolve(RouteValue(json = true, value = "second"))

          text.projection.projectionId shouldBe DIRECT_TEXT_PROJECTION_ID
          json.projection.projectionId shouldBe DIRECT_JSON_PROJECTION_ID
        }
      }

      context("同じruntime classでも次の値だけoriginal persistence round-tripを失うとき") {
        lateinit var handle: CustomInspectionHandle<CacheSettings>
        lateinit var resolver: CustomProjectionResolver<CacheSettings>

        beforeEach {
          handle =
            CustomInspectionHandle
              .forSerializer(
                ValueDependentRoundTripSerializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          resolver = CustomProjectionResolver(handle)
        }

        afterEach {
          handle.close()
        }

        it("cached generated routeもsnapshotごとに再検証して編集可能化しない") {
          resolver.resolve(CacheSettings("safe", 1))
          val failure =
            shouldThrow<CustomInspectionFailure> {
              resolver.resolve(CacheSettings("unsafe", 2))
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE
        }
      }
    }
  })

private enum class OfficialRoute(
  val displayName: String
) {
  FILE_STORAGE("DataStoreFactory serializer overload"),
  OKIO_STORAGE("captured OkioStorage overload"),
  MULTI_PROCESS_STORAGE("MultiProcessDataStoreFactory storage overload")
}

private class OfficialStoreHarness private constructor(
  val directory: File,
  val file: File,
  val scope: CoroutineScope,
  val producerCalls: AtomicInteger,
  val declarationId: String,
  val store: DataStore<Settings>,
  val entry: RegistryEntry,
  val adapter: StoreAdapter
) : AutoCloseable {
  override fun close() {
    scope.cancel()
    DataStoreInspectorRuntime.stop()
    directory.deleteRecursively()
  }

  companion object {
    fun create(
      route: OfficialRoute,
      serializer: Serializer<Settings> = JsonSettingsSerializer()
    ): OfficialStoreHarness {
      DataStoreInspectorRuntime.stop()
      val directory = Files.createTempDirectory("datastore-inspector-custom").toFile()
      val file = File(directory, "${route.name.lowercase()}.settings")
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      val producerCalls = AtomicInteger()
      val declarationId = "custom-${route.name}-${System.nanoTime()}"
      val store =
        when (route) {
          OfficialRoute.FILE_STORAGE ->
            DataStoreCreationBridge.createSingleProcess(
              factory = DataStoreFactory,
              serializer = serializer,
              corruptionHandler = null,
              migrations = emptyList(),
              scope = scope,
              produceFile = {
                producerCalls.incrementAndGet()
                file
              },
              declarationId = declarationId,
              declarationOwner = "fixture.CustomStores",
              declarationProperty = "settings"
            )
          OfficialRoute.OKIO_STORAGE -> {
            val storage =
              DataStoreCreationBridge.okioStorageDefault(
                fileSystem = FileSystem.SYSTEM,
                serializer = JsonSettingsOkioSerializer,
                coordinatorProducer = null,
                producePath = {
                  producerCalls.incrementAndGet()
                  file.absolutePath.toPath()
                },
                mask = 4,
                marker = null
              )
            DataStoreCreationBridge.createSingleProcessFromStorage(
              factory = DataStoreFactory,
              storage = storage,
              corruptionHandler = null,
              migrations = emptyList(),
              scope = scope,
              declarationId = declarationId,
              declarationOwner = "fixture.CustomStores",
              declarationProperty = "settings"
            )
          }
          OfficialRoute.MULTI_PROCESS_STORAGE -> {
            val storage =
              DataStoreCreationBridge.fileStorageDefault(
                serializer = serializer,
                coordinatorProducer = null,
                produceFile = {
                  producerCalls.incrementAndGet()
                  file
                },
                mask = 2,
                marker = null
              )
            DataStoreCreationBridge.createMultiProcessFromStorage(
              factory = MultiProcessDataStoreFactory,
              storage = storage,
              corruptionHandler = null,
              migrations = emptyList(),
              scope = scope,
              declarationId = declarationId,
              declarationOwner = "fixture.CustomStores",
              declarationProperty = "settings"
            )
          }
        }
      val entry =
        DataStoreInspectorRuntime.registry().entries().single {
          it.declaration.declarationId == declarationId
        }
      val adapter =
        entry.state.shouldBeInstanceOf<RegistryState.Resolved>().adapter
      return OfficialStoreHarness(
        directory,
        file,
        scope,
        producerCalls,
        declarationId,
        store,
        entry,
        adapter
      )
    }
  }
}

private data class Settings(
  val label: String,
  val counter: Int
)

private open class JsonSettingsSerializer : Serializer<Settings> {
  override val defaultValue: Settings = Settings("before", 1)

  override suspend fun readFrom(input: InputStream): Settings = decodeSettings(input.readBytes().decodeToString())

  override suspend fun writeTo(
    t: Settings,
    output: OutputStream
  ) {
    output.write(encodeSettings(t).encodeToByteArray())
  }
}

private object JsonSettingsOkioSerializer : OkioSerializer<Settings> {
  override val defaultValue: Settings = Settings("before", 1)

  override suspend fun readFrom(source: BufferedSource): Settings = decodeSettings(source.readUtf8())

  override suspend fun writeTo(
    t: Settings,
    sink: BufferedSink
  ) {
    sink.writeUtf8(encodeSettings(t))
  }
}

private class ActualMismatchSerializer : JsonSettingsSerializer() {
  private val candidateWrites = AtomicInteger()

  override suspend fun writeTo(
    t: Settings,
    output: OutputStream
  ) {
    val actual =
      if (t.label == "edited" && candidateWrites.incrementAndGet() >= 3) {
        Settings("wrong", 99)
      } else {
        t
      }
    super.writeTo(actual, output)
  }
}

private enum class ActualPostconditionThrowMode(
  val displayName: String
) {
  DECODE("decode"),
  PROJECTION_VERIFICATION("projection verification")
}

private class ActualPostconditionThrowSerializer(
  private val mode: ActualPostconditionThrowMode
) : JsonSettingsSerializer() {
  private val candidateWrites = AtomicInteger()

  override suspend fun readFrom(input: InputStream): Settings {
    val bytes = input.readBytes()
    if (
      mode == ActualPostconditionThrowMode.PROJECTION_VERIFICATION &&
      bytes.contentEquals(INVALID_UTF8)
    ) {
      return Settings("edited", 2)
    }
    return decodeSettings(bytes.decodeToString())
  }

  override suspend fun writeTo(
    t: Settings,
    output: OutputStream
  ) {
    if (t.label == "edited" && candidateWrites.incrementAndGet() >= 3) {
      val bytes =
        when (mode) {
          ActualPostconditionThrowMode.DECODE ->
            "{".encodeToByteArray()
          ActualPostconditionThrowMode.PROJECTION_VERIFICATION ->
            INVALID_UTF8
        }
      output.write(bytes)
    } else {
      super.writeTo(t, output)
    }
  }

  private companion object {
    val INVALID_UTF8: ByteArray = byteArrayOf(0xC3.toByte())
  }
}

private enum class ActualControlFlowThrowMode(
  val displayName: String,
  val reason: CustomStoreReasonCode
) {
  CANCELLATION(
    "CancellationException",
    CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
  ),
  FATAL(
    "LinkageError",
    CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
  )
  ;

  fun createThrowable(): Throwable =
    when (this) {
      CANCELLATION -> CancellationException("actual decode cancelled")
      FATAL -> LinkageError("actual decode linkage failure")
    }
}

private enum class CandidateControlFlowPhase(
  val displayName: String
) {
  DECODE("decode"),
  ENCODE("encode")
}

private class CandidateControlFlowSerializer(
  private val phase: CandidateControlFlowPhase,
  private val failure: Throwable
) : JsonSettingsSerializer() {
  override suspend fun readFrom(input: InputStream): Settings {
    val document = input.readBytes().decodeToString()
    if (
      phase == CandidateControlFlowPhase.DECODE &&
      document.contains("\"label\":\"edited\"")
    ) {
      throw failure
    }
    return decodeSettings(document)
  }

  override suspend fun writeTo(
    t: Settings,
    output: OutputStream
  ) {
    if (
      phase == CandidateControlFlowPhase.ENCODE &&
      t.label == "edited"
    ) {
      throw failure
    }
    super.writeTo(t, output)
  }
}

private class ActualControlFlowThrowSerializer(
  private val failure: Throwable
) : JsonSettingsSerializer() {
  private val candidateWrites = AtomicInteger()

  override suspend fun readFrom(input: InputStream): Settings {
    val document = input.readBytes().decodeToString()
    if (document == CONTROL_FLOW_DOCUMENT) throw failure
    return decodeSettings(document)
  }

  override suspend fun writeTo(
    t: Settings,
    output: OutputStream
  ) {
    if (t.label == "edited" && candidateWrites.incrementAndGet() >= 3) {
      output.write(CONTROL_FLOW_DOCUMENT.encodeToByteArray())
    } else {
      super.writeTo(t, output)
    }
  }

  private companion object {
    const val CONTROL_FLOW_DOCUMENT: String = "\"actual-control-flow\""
  }
}

private fun encodeSettings(value: Settings): String =
  buildJsonObject {
    put("label", value.label)
    put("counter", value.counter)
  }.toString()

private fun decodeSettings(document: String): Settings {
  val root = Json.parseToJsonElement(document).jsonObject
  return Settings(
    label = root.getValue("label").jsonPrimitive.content,
    counter = root.getValue("counter").jsonPrimitive.int
  )
}

private class CoarseSettings(
  val label: String,
  val hidden: String
) {
  override fun equals(other: Any?): Boolean = other is CoarseSettings && label == other.label

  override fun hashCode(): Int = hidden.hashCode()
}

private object CoarseSerializer : Serializer<CoarseSettings> {
  val writeCalls = AtomicInteger()
  val editedWriteCalls = AtomicInteger()
  override val defaultValue: CoarseSettings = CoarseSettings("same", "original")

  override suspend fun readFrom(input: InputStream): CoarseSettings {
    val root = Json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject
    return CoarseSettings(
      root.getValue("label").jsonPrimitive.content,
      root.getValue("hidden").jsonPrimitive.content
    )
  }

  override suspend fun writeTo(
    t: CoarseSettings,
    output: OutputStream
  ) {
    writeCalls.incrementAndGet()
    if (t.hidden == "edited") editedWriteCalls.incrementAndGet()
    output.write(
      buildJsonObject {
        put("label", t.label)
        put("hidden", t.hidden)
      }.toString().encodeToByteArray()
    )
  }
}

private class CoarseEqualityHarness private constructor(
  val directory: File,
  val file: File,
  val scope: CoroutineScope,
  val store: DataStore<CoarseSettings>,
  val adapter: StoreAdapter
) : AutoCloseable {
  override fun close() {
    scope.cancel()
    DataStoreInspectorRuntime.stop()
    directory.deleteRecursively()
  }

  companion object {
    fun create(): CoarseEqualityHarness {
      DataStoreInspectorRuntime.stop()
      val directory = Files.createTempDirectory("datastore-inspector-coarse").toFile()
      val file = File(directory, "coarse.settings")
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      val declarationId = "coarse-${System.nanoTime()}"
      val store =
        DataStoreCreationBridge.createSingleProcess(
          DataStoreFactory,
          CoarseSerializer,
          null,
          emptyList(),
          scope,
          { file },
          declarationId,
          "fixture.Coarse",
          "settings"
        )
      val entry =
        DataStoreInspectorRuntime.registry().entries().single {
          it.declaration.declarationId == declarationId
        }
      val adapter = entry.state.shouldBeInstanceOf<RegistryState.Resolved>().adapter
      return CoarseEqualityHarness(directory, file, scope, store, adapter)
    }
  }
}

private object BinarySettingsSerializer : Serializer<Settings> {
  override val defaultValue: Settings = Settings("before", 1)

  override suspend fun readFrom(input: InputStream): Settings =
    DataInputStream(input).use { data ->
      Settings(data.readUTF(), data.readInt())
    }

  override suspend fun writeTo(
    t: Settings,
    output: OutputStream
  ) {
    DataOutputStream(output).use { data ->
      data.writeUTF(t.label)
      data.writeInt(t.counter)
    }
  }
}

private class SettingsCodec(
  override val codecId: String
) : InspectorCustomCodec<Settings> {
  override val format: CustomDocumentFormat = CustomDocumentFormat.JSON

  override fun encode(value: Settings): String = encodeSettings(value)

  override fun decode(document: String): Settings = decodeSettings(document)
}

private fun codecBinding(codec: InspectorCustomCodec<Settings>): InspectorCustomCodecBinding<Settings> =
  InspectorCustomCodecBinding(
    serializerClass = BinarySettingsSerializer::class.java,
    valueClass = Settings::class.java,
    codec = codec
  )

private fun codecProvider(
  providerId: String,
  codec: InspectorCustomCodec<Settings>
): InspectorCustomCodecBindingProvider =
  object : InspectorCustomCodecBindingProvider {
    override val providerId: String = providerId

    override fun bindings(): List<InspectorCustomCodecBinding<*>> = listOf(codecBinding(codec))
  }

private val BinaryCodecProvider =
  codecProvider("binary-settings", SettingsCodec("settings"))

private class BinaryStoreHarness private constructor(
  val directory: File,
  val scope: CoroutineScope,
  val store: DataStore<Settings>,
  val adapter: StoreAdapter
) : AutoCloseable {
  override fun close() {
    scope.cancel()
    DataStoreInspectorRuntime.stop()
    directory.deleteRecursively()
  }

  companion object {
    fun create(): BinaryStoreHarness {
      DataStoreInspectorRuntime.stop()
      val directory = Files.createTempDirectory("datastore-inspector-binary").toFile()
      val file = File(directory, "binary.settings")
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      val declarationId = "binary-${System.nanoTime()}"
      val store =
        DataStoreCreationBridge.createSingleProcess(
          DataStoreFactory,
          BinarySettingsSerializer,
          null,
          emptyList(),
          scope,
          { file },
          declarationId,
          "fixture.Binary",
          "settings"
        )
      val entry =
        DataStoreInspectorRuntime.registry().entries().single {
          it.declaration.declarationId == declarationId
        }
      val adapter = entry.state.shouldBeInstanceOf<RegistryState.Resolved>().adapter
      return BinaryStoreHarness(directory, scope, store, adapter)
    }
  }
}

@Serializable
private data class CacheSettings(
  val label: String,
  val counter: Int
)

private class CountingGeneratedSerializer : Serializer<CacheSettings> {
  val writeCalls = AtomicInteger()
  val readCalls = AtomicInteger()
  override val defaultValue: CacheSettings = CacheSettings("", 0)

  override suspend fun readFrom(input: InputStream): CacheSettings =
    DataInputStream(input).use { data ->
      readCalls.incrementAndGet()
      CacheSettings(data.readUTF(), data.readInt())
    }

  override suspend fun writeTo(
    t: CacheSettings,
    output: OutputStream
  ) {
    writeCalls.incrementAndGet()
    DataOutputStream(output).use { data ->
      data.writeUTF(t.label)
      data.writeInt(t.counter)
    }
  }
}

private class ValueDependentLossySerializer : Serializer<CacheSettings> {
  val readCalls = AtomicInteger()
  override val defaultValue: CacheSettings = CacheSettings("", 0)

  override suspend fun readFrom(input: InputStream): CacheSettings =
    DataInputStream(input).use { data ->
      readCalls.incrementAndGet()
      CacheSettings(data.readUTF(), data.readInt())
    }

  override suspend fun writeTo(
    t: CacheSettings,
    output: OutputStream
  ) {
    DataOutputStream(output).use { data ->
      data.writeUTF(t.label)
      data.writeInt(if (t.label == "lossy") 0 else t.counter)
    }
  }
}

private data class FallbackCacheValue(
  val label: String,
  val counter: Int
)

private class FallbackCacheSerializer : Serializer<FallbackCacheValue> {
  val writeCalls = AtomicInteger()
  val readCalls = AtomicInteger()
  override val defaultValue: FallbackCacheValue = FallbackCacheValue("", 0)

  override suspend fun readFrom(input: InputStream): FallbackCacheValue =
    DataInputStream(input).use { data ->
      readCalls.incrementAndGet()
      FallbackCacheValue(data.readUTF(), data.readInt())
    }

  override suspend fun writeTo(
    t: FallbackCacheValue,
    output: OutputStream
  ) {
    writeCalls.incrementAndGet()
    DataOutputStream(output).use { data ->
      data.writeUTF(t.label)
      data.writeInt(t.counter)
    }
  }
}

private class FallbackCacheProvider(
  private val serializerClass: Class<*>
) : InspectorCustomCodecBindingProvider {
  val bindingsCalls = AtomicInteger()
  override val providerId: String = "fallback-cache-provider"

  override fun bindings(): List<InspectorCustomCodecBinding<*>> {
    bindingsCalls.incrementAndGet()
    return listOf(
      InspectorCustomCodecBinding(
        serializerClass = serializerClass,
        valueClass = FallbackCacheValue::class.java,
        codec = FallbackCacheCodec
      )
    )
  }
}

private object FallbackCacheCodec : InspectorCustomCodec<FallbackCacheValue> {
  override val codecId: String = "cache-value"
  override val format: CustomDocumentFormat = CustomDocumentFormat.JSON

  override fun encode(value: FallbackCacheValue): String =
    buildJsonObject {
      put("label", value.label)
      put("counter", value.counter)
    }.toString()

  override fun decode(document: String): FallbackCacheValue {
    val root = Json.parseToJsonElement(document).jsonObject
    return FallbackCacheValue(
      label = root.getValue("label").jsonPrimitive.content,
      counter = root.getValue("counter").jsonPrimitive.int
    )
  }
}

private object ValueDependentRoundTripSerializer : Serializer<CacheSettings> {
  override val defaultValue: CacheSettings = CacheSettings("safe", 0)

  override suspend fun readFrom(input: InputStream): CacheSettings =
    DataInputStream(input).use { data ->
      CacheSettings(data.readUTF(), data.readInt())
    }

  override suspend fun writeTo(
    t: CacheSettings,
    output: OutputStream
  ) {
    DataOutputStream(output).use { data ->
      data.writeUTF(t.label)
      data.writeInt(if (t.label == "unsafe") 0 else t.counter)
    }
  }
}

private data class RouteValue(
  val json: Boolean,
  val value: String
)

private object RouteChangingSerializer : Serializer<RouteValue> {
  override val defaultValue: RouteValue = RouteValue(false, "")

  override suspend fun readFrom(input: InputStream): RouteValue {
    val document = input.readBytes().decodeToString()
    return if (document.startsWith("{")) {
      val root = Json.parseToJsonElement(document).jsonObject
      RouteValue(true, root.getValue("value").jsonPrimitive.content)
    } else {
      RouteValue(false, document.removePrefix("value="))
    }
  }

  override suspend fun writeTo(
    t: RouteValue,
    output: OutputStream
  ) {
    val document =
      if (t.json) {
        buildJsonObject { put("value", t.value) }.toString()
      } else {
        "value=${t.value}"
      }
    output.write(document.encodeToByteArray())
  }
}
