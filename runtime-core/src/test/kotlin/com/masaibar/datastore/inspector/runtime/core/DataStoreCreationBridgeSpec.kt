package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import com.google.protobuf.StringValue
import com.masaibar.datastore.inspector.protocol.StorageScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.BufferedSink
import okio.BufferedSource
import okio.Path.Companion.toPath
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DataStoreCreationBridgeSpec :
  DescribeSpec({
    describe("selectSerializerForInspection") {
      context("defaultValueがMessageLiteのSerializerのとき") {
        val serializer = ProtoSerializer

        it("wrapperとinspection handleを作らずoriginalを維持する") {
          val selected =
            selectSerializerForInspection(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            )

          selected.effective shouldBeSameInstanceAs serializer
          selected.handle.shouldBeNull()
        }
      }

      context("defaultValueがMessageLiteのOkioSerializerのとき") {
        val serializer = ProtoOkioSerializer

        it("Okio routeでもoriginalを維持する") {
          val selected =
            selectSerializerForInspection(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            )

          selected.effective shouldBeSameInstanceAs serializer
          selected.handle.shouldBeNull()
        }
      }

      context("Custom SerializerのdefaultValue getterに副作用があるとき") {
        val serializer = CountingDefaultSerializer()

        it("選択・handle作成・wrapper参照を通してoriginal getterを1回だけ呼ぶ") {
          val selected =
            selectSerializerForInspection(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            )
          val first = selected.effective.defaultValue
          val second = selected.effective.defaultValue

          first shouldBe "default-1"
          second shouldBe "default-1"
          selected.handle?.defaultValue shouldBe "default-1"
          serializer.defaultCalls.get() shouldBe 1
          selected.handle?.close()
        }
      }

      context("Custom OkioSerializerのdefaultValue getterに副作用があるとき") {
        val serializer = CountingDefaultOkioSerializer()

        it("Okio wrapperも最初に捕捉した値だけを返す") {
          val selected =
            selectSerializerForInspection(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            )
          val first = selected.effective.defaultValue
          val second = selected.effective.defaultValue

          first shouldBe "default-1"
          second shouldBe "default-1"
          selected.handle?.defaultValue shouldBe "default-1"
          serializer.defaultCalls.get() shouldBe 1
          selected.handle?.close()
        }
      }
    }

    describe("produceFileとproducePathの観測") {
      context("appのproducerをbridgeでwrapしたとき") {
        lateinit var directory: File
        lateinit var file: File
        lateinit var fileCalls: AtomicInteger
        lateinit var pathCalls: AtomicInteger
        lateinit var fileName: ObservedStoreName
        lateinit var pathName: ObservedStoreName
        lateinit var wrappedFile: () -> File
        lateinit var wrappedPath: () -> okio.Path

        beforeEach {
          directory = Files.createTempDirectory("datastore-inspector-name").toFile()
          file = File(directory, "only-basename.preferences_pb")
          fileCalls = AtomicInteger()
          pathCalls = AtomicInteger()
          fileName = ObservedStoreName()
          pathName = ObservedStoreName()
          wrappedFile =
            observeFileProducer(
              producer = {
                fileCalls.incrementAndGet()
                file
              },
              observedName = fileName
            )
          wrappedPath =
            observePathProducer(
              producer = {
                pathCalls.incrementAndGet()
                file.absolutePath.toPath()
              },
              observedName = pathName
            )
        }

        afterEach {
          directory.deleteRecursively()
        }

        it("wrap時には評価せず実呼出し1回につきapp producerを1回だけ評価する") {
          fileCalls.get() shouldBe 0
          pathCalls.get() shouldBe 0

          wrappedFile() shouldBe file
          wrappedPath() shouldBe file.absolutePath.toPath()

          fileCalls.get() shouldBe 1
          pathCalls.get() shouldBe 1
          fileName.current() shouldBe file.name
          pathName.current() shouldBe file.name
          fileName.current() shouldBe "only-basename.preferences_pb"
          pathName.current() shouldBe "only-basename.preferences_pb"
        }
      }
    }

    describe("Proto creation route") {
      context("single-processとmulti-process serializer overloadを通るとき") {
        lateinit var directory: File
        lateinit var scope: CoroutineScope
        lateinit var producerCalls: AtomicInteger
        lateinit var stores: List<DataStore<StringValue>>
        lateinit var declarationIds: List<String>

        beforeEach {
          DataStoreInspectorRuntime.stop()
          directory = Files.createTempDirectory("datastore-inspector-proto").toFile()
          scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          producerCalls = AtomicInteger()
          declarationIds =
            listOf(
              "proto-single-${System.nanoTime()}",
              "proto-multi-${System.nanoTime()}"
            )
          stores =
            listOf(
              DataStoreCreationBridge.createSingleProcess(
                factory = DataStoreFactory,
                serializer = ProtoSerializer,
                corruptionHandler = null,
                migrations = emptyList(),
                scope = scope,
                produceFile = {
                  producerCalls.incrementAndGet()
                  File(directory, "single.pb")
                },
                declarationId = declarationIds[0],
                declarationOwner = "fixture.ProtoStores",
                declarationProperty = "single"
              ),
              DataStoreCreationBridge.createMultiProcess(
                factory = MultiProcessDataStoreFactory,
                serializer = ProtoSerializer,
                corruptionHandler = null,
                migrations = emptyList(),
                scope = scope,
                produceFile = {
                  producerCalls.incrementAndGet()
                  File(directory, "multi.pb")
                },
                declarationId = declarationIds[1],
                declarationOwner = "fixture.ProtoStores",
                declarationProperty = "multi"
              )
            )
        }

        afterEach {
          scope.cancel()
          DataStoreInspectorRuntime.stop()
          directory.deleteRecursively()
        }

        it("original serializerのまま登録しcreation時にproducerを追加評価しない") {
          val entries =
            DataStoreInspectorRuntime.registry().entries().filter {
              it.declaration.declarationId in declarationIds
            }

          producerCalls.get() shouldBe 0
          entries shouldHaveSize 2
          entries.all {
            it.declaration.kindHint ==
              com.masaibar.datastore.inspector.protocol.StoreKind.PROTO
          } shouldBe true
          stores.forEach { store ->
            CustomInspectionRegistry.handleForStore(store).shouldBeNull()
          }
        }
      }
    }
  })

private object ProtoSerializer : Serializer<StringValue> {
  override val defaultValue: StringValue = StringValue.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): StringValue = StringValue.parseFrom(input)

  override suspend fun writeTo(
    t: StringValue,
    output: OutputStream
  ) {
    t.writeTo(output)
  }
}

private object ProtoOkioSerializer : OkioSerializer<StringValue> {
  override val defaultValue: StringValue = StringValue.getDefaultInstance()

  override suspend fun readFrom(source: BufferedSource): StringValue = StringValue.parseFrom(source.readByteArray())

  override suspend fun writeTo(
    t: StringValue,
    sink: BufferedSink
  ) {
    sink.write(t.toByteArray())
  }
}

private class CountingDefaultSerializer : Serializer<String> {
  val defaultCalls = AtomicInteger()
  override val defaultValue: String
    get() = "default-${defaultCalls.incrementAndGet()}"

  override suspend fun readFrom(input: InputStream): String = input.readBytes().decodeToString()

  override suspend fun writeTo(
    t: String,
    output: OutputStream
  ) {
    output.write(t.encodeToByteArray())
  }
}

private class CountingDefaultOkioSerializer : OkioSerializer<String> {
  val defaultCalls = AtomicInteger()
  override val defaultValue: String
    get() = "default-${defaultCalls.incrementAndGet()}"

  override suspend fun readFrom(source: BufferedSource): String = source.readUtf8()

  override suspend fun writeTo(
    t: String,
    sink: BufferedSink
  ) {
    sink.writeUtf8(t)
  }
}
