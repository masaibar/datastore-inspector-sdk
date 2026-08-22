@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.Serializer
import com.masaibar.datastore.inspector.protocol.CustomDocumentFormat
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.StorageScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Contextual
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class CustomProjectionSpec :
  DescribeSpec({
    describe("CustomProjectionResolver.resolve") {
      context("実Serializerがnested値とUnicodeを含むstrict JSONを可逆化するとき") {
        val serializer = DirectJsonSerializer
        val current =
          DirectJsonValue(
            label = "こんにちは🐼",
            counters = listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE),
            nested = mapOf("empty" to null, "enabled" to true)
          )
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("direct JSON projectionを最優先で解決する") {
          val projected = resolver.resolve(current)

          projected.projection.projectionId shouldBe DIRECT_JSON_PROJECTION_ID
          projected.projection.format shouldBe CustomDocumentFormat.JSON
          projected.projection.decode(projected.document) shouldBe current
        }
      }

      context("direct JSONとcaptured/generated projectionが異なるdocumentへ完全往復するとき") {
        val current = DualProjectionValue("same-current")
        val handle =
          CustomInspectionHandle
            .forSerializer(
              DualProjectionJsonSerializer(enveloped = true),
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("優先候補だけを採用せずambiguityへfail closedする") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              resolver.resolve(current)
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
        }
      }

      context("direct JSONとcaptured/generated projectionが同じcanonical documentへ往復するとき") {
        val current = DualProjectionValue("same-current")
        val handle =
          CustomInspectionHandle
            .forSerializer(
              DualProjectionJsonSerializer(enveloped = false),
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("全候補の一致を確認して既存優先順のdirect JSONを採用する") {
          val projected = resolver.resolve(current)

          projected.projection.projectionId shouldBe DIRECT_JSON_PROJECTION_ID
          projected.document shouldBe """{"value":"same-current"}"""
          handle.structuredContracts().size shouldBe 1
        }
      }

      context("auto projectionとresolved debug codecが異なるdocumentへ完全往復するとき") {
        val current = DualProjectionValue("same-current")
        val serializer = DualProjectionJsonSerializer(enveloped = false)
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        beforeEach {
          InspectorCustomCodecRegistry.replaceForTest(
            listOf(dualProjectionCodecProvider(serializer.javaClass))
          )
        }
        afterEach {
          handle.close()
          InspectorCustomCodecRegistry.clear()
        }

        it("auto候補を先に採用せずambiguityへfail closedする") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              resolver.resolve(current)
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
        }
      }

      context("auto projection成功時にもdebug codec bindingが重複するとき") {
        val current = DualProjectionValue("same-current")
        val serializer = DualProjectionJsonSerializer(enveloped = false)
        val binding =
          InspectorCustomCodecBinding(
            serializerClass = serializer.javaClass,
            valueClass = DualProjectionValue::class.java,
            codec = AlternateDualProjectionCodec
          )
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        beforeEach {
          InspectorCustomCodecRegistry.replaceForTest(
            listOf(
              object : InspectorCustomCodecBindingProvider {
                override val providerId: String = "duplicate-dual-projection"

                override fun bindings(): List<InspectorCustomCodecBinding<*>> =
                  listOf(binding, binding)
              }
            )
          )
        }
        afterEach {
          handle.close()
          InspectorCustomCodecRegistry.clear()
        }

        it("auto候補の有無にかかわらずambiguityへfail closedする") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              resolver.resolve(current)
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
        }
      }

      context("暗号化後段を持つCBOR Serializerが実KSerializerをhookへ渡すとき") {
        val serializer = EncryptedCborSerializer()
        val current = StructuredValue("cbor", listOf(1, 2, 3))
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("raw bytesを公開せずcaptured structured projectionを解決する") {
          val projected = resolver.resolve(current)

          projected.projection.projectionId shouldBe STRUCTURED_PROJECTION_ID
          projected.projection.format shouldBe CustomDocumentFormat.JSON
          projected.projection.decode(projected.document) shouldBe current
        }
      }

      context("contextual serializerと実SerializersModuleをCBOR callから捕捉したとき") {
        val serializer = ContextualCborSerializer()
        val current = ContextualValue(ContextToken("module-value"))
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("captured moduleをInspector-owned JSONへ引き継ぐ") {
          val projected = resolver.resolve(current)

          projected.projection.projectionId shouldBe STRUCTURED_PROJECTION_ID
          projected.projection.decode(projected.document) shouldBe current
        }
      }

      context("単純なnon-generic Serializable値をbinary Serializerが保存するとき") {
        val serializer = GeneratedBinarySerializer
        val current = GeneratedValue("generated", 42)
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("生成KSerializerを全probe後に採用する") {
          val projected = resolver.resolve(current)

          projected.projection.projectionId shouldBe GENERATED_PROJECTION_ID
          projected.projection.decode(projected.document) shouldBe current
        }
      }

      context("strict UTF-8 text Serializerがexact round-tripするとき") {
        val serializer = TextSerializer
        val current = TextValue("label=安全\ncounter=42")
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("形式を推測せずdirect text projectionを採用する") {
          val projected = resolver.resolve(current)

          projected.projection.projectionId shouldBe DIRECT_TEXT_PROJECTION_ID
          projected.projection.format shouldBe CustomDocumentFormat.TEXT
          projected.document shouldBe current.document
        }
      }

      context("JSON-like出力にduplicate keyがあるとき") {
        val serializer = DuplicateJsonSerializer
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("plain textへ格下げせずread-onlyにする") {
          shouldThrow<CustomInspectionFailure> {
            resolver.resolve(LossyValue("same", "state"))
          }.reason shouldBe CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_JSON
        }
      }

      context("documentは一致するがdecodeで状態を失うSerializerのとき") {
        val serializer = LossySerializer
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("value equality gateで編集可能化しない") {
          shouldThrow<CustomInspectionFailure> {
            resolver.resolve(LossyValue("visible", "must-not-disappear"))
          }.reason shouldBe CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
        }
      }

      context("同じ値のtext出力が非決定的なとき") {
        lateinit var serializer: NonDeterministicTextSerializer
        lateinit var handle: CustomInspectionHandle<TextValue>
        lateinit var resolver: CustomProjectionResolver<TextValue>

        beforeEach {
          serializer = NonDeterministicTextSerializer()
          handle =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          resolver = CustomProjectionResolver(handle)
        }
        afterEach { handle.close() }

        it("非決定性を検出してread-onlyにする") {
          shouldThrow<CustomInspectionFailure> {
            resolver.resolve(TextValue("value"))
          }.reason shouldBe CustomStoreReasonCode.CUSTOM_SERIALIZER_NON_DETERMINISTIC
        }
      }

      context("実CBOR SerializerのrootがJvmInline value classのとき") {
        val current = InlineRoot("inline-secret")
        val handle =
          CustomInspectionHandle
            .forSerializer(
              InlineRootCborSerializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("generic callで再boxingされてもexact value-like rootを捕捉しprojection不一致は拒否する") {
          handle.encodeForInspection(current)

          handle.structuredContracts().size shouldBe 1
          shouldThrow<CustomInspectionFailure> {
            resolver.resolve(current)
          }.reason shouldBe
            CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
        }
      }

      context("通常data classとequalsな別instanceをnested structured callへ渡すとき") {
        val current = EqualNormalRoot("same")
        val handle =
          CustomInspectionHandle
            .forSerializer(
              EqualButDistinctNestedSerializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first

        afterEach { handle.close() }

        it("通常classのidentity条件を緩和せずroot contractとして捕捉しない") {
          handle.encodeForInspection(current)

          handle.structuredContracts().size shouldBe 0
        }
      }

      context("Serializerが毎回新しいroot contract identityを生成するとき") {
        val current = EqualNormalRoot("secret-document-must-not-be-retained")
        val serializer = DynamicContractSerializer()
        val handle =
          CustomInspectionHandle
            .forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            ).first
        val resolver = CustomProjectionResolver(handle)

        afterEach { handle.close() }

        it("保持を2 contractで打ち切りambiguityを安定してfail closedする") {
          repeat(100) { handle.encodeForInspection(current) }
          val failures =
            List(10) {
              shouldThrow<CustomInspectionFailure> {
                resolver.resolve(current)
              }.reason
            }

          handle.structuredContracts().size shouldBe 2
          failures.toSet() shouldBe
            setOf(CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS)
        }
      }

      ProjectionControlFlowMode.entries.forEach { mode ->
        context("initial encodeがcause chain内に${mode.displayName}を含むとき") {
          lateinit var controlFlow: ProjectionControlFlow
          lateinit var handle: CustomInspectionHandle<TextValue>
          lateinit var resolver: CustomProjectionResolver<TextValue>

          beforeEach {
            controlFlow = mode.create()
            handle =
              CustomInspectionHandle
                .forSerializer(
                  InitialControlFlowSerializer(controlFlow.wrapper),
                  StorageScope.CREDENTIAL_PROTECTED
                ).first
            resolver = CustomProjectionResolver(handle)
          }
          afterEach { handle.close() }

          it("最深の制御フロー例外を同一identityで再throwする") {
            val caught =
              shouldThrow<Throwable> {
                resolver.resolve(TextValue("value"))
              }

            (caught === controlFlow.original) shouldBe true
          }
        }

        context("cached structured routeの再検証が${mode.displayName}を含むとき") {
          lateinit var controlFlow: ProjectionControlFlow
          lateinit var serializer: CachedControlFlowSerializer
          lateinit var handle: CustomInspectionHandle<CachedControlFlowValue>
          lateinit var resolver: CustomProjectionResolver<CachedControlFlowValue>
          val current = CachedControlFlowValue("cached")

          beforeEach {
            controlFlow = mode.create()
            serializer = CachedControlFlowSerializer()
            handle =
              CustomInspectionHandle
                .forSerializer(
                  serializer,
                  StorageScope.CREDENTIAL_PROTECTED
                ).first
            resolver = CustomProjectionResolver(handle)
            resolver.resolve(current)
            serializer.failure = controlFlow.wrapper
          }
          afterEach { handle.close() }

          it("cached route内で通常失敗へ変換せず最深例外を再throwする") {
            val caught =
              shouldThrow<Throwable> {
                resolver.resolve(current)
              }

            (caught === controlFlow.original) shouldBe true
          }
        }
      }
    }

    describe("StructuredSerializationCapture resource bounds") {
      context("同じroot contract hookを10万回呼ぶとき") {
        val root = CaptureRoot("same")
        val format = FixedStringFormat()
        val serializer = CaptureRoot.serializer()

        it("online dedupeにより1 contractだけを返す") {
          val contracts =
            StructuredSerializationCapture.captureWrite(root) {
              repeat(100_000) {
                StructuredSerializationCapture.encodeToString(
                  format,
                  serializer,
                  root
                )
              }
            }

          contracts.size shouldBe 1
        }
      }

      context("同じrootからdistinct contractを大量に観測するとき") {
        val root = CaptureRoot("distinct")
        val format = FixedStringFormat()

        it("encode側の保持を2 contractで打ち切る") {
          val contracts =
            StructuredSerializationCapture.captureWrite(root) {
              repeat(100_000) {
                StructuredSerializationCapture.encodeToString(
                  format,
                  DelegatingCaptureSerializer(),
                  root
                )
              }
            }

          contracts.size shouldBe 2
        }
      }

      context("structured concurrencyでencode/decode hookが並行実行されるとき") {
        val root = CaptureRoot("parallel")
        val encodeFormat = FixedStringFormat()
        val decodeFormat = FixedStringFormat { CaptureRoot("parallel") }
        val serializer = CaptureRoot.serializer()

        it("session内の同一性mapとcontract listを破損せず捕捉する") {
          val encodedContracts =
            StructuredSerializationCapture.captureWrite(root) {
              coroutineScope {
                List(512) {
                  async(Dispatchers.Default) {
                    StructuredSerializationCapture.encodeToString(
                      encodeFormat,
                      serializer,
                      root
                    )
                  }
                }.awaitAll()
              }
            }
          val (_, decodedContracts) =
            StructuredSerializationCapture.captureRead {
              coroutineScope {
                List(8) {
                  async(Dispatchers.Default) {
                    StructuredSerializationCapture.decodeFromString(
                      decodeFormat,
                      serializer,
                      "ignored"
                    )
                  }
                }.awaitAll().first()
              }
            }

          encodedContracts.size shouldBe 1
          decodedContracts.size shouldBe 1
        }
      }

      context("decode hookが10万個のdistinct root候補を生成するとき") {
        val format = FixedStringFormat { CaptureRoot(System.nanoTime().toString()) }
        val serializer = CaptureRoot.serializer()

        it("固定上限で候補を破棄しcapture unavailableへ縮退する") {
          val (_, contracts) =
            StructuredSerializationCapture.captureRead {
              var last = CaptureRoot("initial")
              repeat(100_000) {
                last =
                  StructuredSerializationCapture.decodeFromString(
                    format,
                    serializer,
                    "ignored"
                  )
              }
              last
            }

          contracts.size shouldBe 0
        }
      }
    }
  })

@Serializable
private data class DirectJsonValue(
  val label: String,
  val counters: List<Int>,
  val nested: Map<String, Boolean?>
)

private object DirectJsonSerializer : Serializer<DirectJsonValue> {
  private val json =
    Json {
      encodeDefaults = true
      explicitNulls = true
    }
  override val defaultValue = DirectJsonValue("", emptyList(), emptyMap())

  override suspend fun readFrom(input: InputStream): DirectJsonValue =
    json.decodeFromString(
      DirectJsonValue.serializer(),
      input.readBytes().decodeToString()
    )

  override suspend fun writeTo(
    t: DirectJsonValue,
    output: OutputStream
  ) {
    output.write(json.encodeToString(DirectJsonValue.serializer(), t).encodeToByteArray())
  }
}

@Serializable
private data class DualProjectionValue(
  val value: String
)

private class DualProjectionJsonSerializer(
  private val enveloped: Boolean
) : Serializer<DualProjectionValue> {
  override val defaultValue: DualProjectionValue = DualProjectionValue("")

  override suspend fun readFrom(input: InputStream): DualProjectionValue {
    val persistedDocument = input.readBytes().decodeToString()
    val projectionDocument =
      if (enveloped) {
        Json.parseToJsonElement(persistedDocument)
          .jsonObject
          .getValue("payload")
          .toString()
      } else {
        persistedDocument
      }
    return StructuredSerializationCapture.decodeFromString(
      Json,
      DualProjectionValue.serializer(),
      projectionDocument
    )
  }

  override suspend fun writeTo(
    t: DualProjectionValue,
    output: OutputStream
  ) {
    val projectionDocument =
      StructuredSerializationCapture.encodeToString(
        Json,
        DualProjectionValue.serializer(),
        t
      )
    val persistedDocument =
      if (enveloped) {
        """{"payload":$projectionDocument}"""
      } else {
        projectionDocument
      }
    output.write(persistedDocument.encodeToByteArray())
  }
}

@Serializable
private data class AlternateDualProjectionDocument(
  val alternate: String
)

private object AlternateDualProjectionCodec : InspectorCustomCodec<DualProjectionValue> {
  override val codecId: String = "alternate-dual-projection"
  override val format: CustomDocumentFormat = CustomDocumentFormat.JSON

  override fun encode(value: DualProjectionValue): String =
    Json.encodeToString(
      AlternateDualProjectionDocument.serializer(),
      AlternateDualProjectionDocument(value.value)
    )

  override fun decode(document: String): DualProjectionValue =
    DualProjectionValue(
      Json
        .decodeFromString(
          AlternateDualProjectionDocument.serializer(),
          document
        ).alternate
    )
}

private fun dualProjectionCodecProvider(
  serializerClass: Class<*>
): InspectorCustomCodecBindingProvider =
  object : InspectorCustomCodecBindingProvider {
    override val providerId: String = "dual-projection-codec"

    override fun bindings(): List<InspectorCustomCodecBinding<*>> =
      listOf(
        InspectorCustomCodecBinding(
          serializerClass = serializerClass,
          valueClass = DualProjectionValue::class.java,
          codec = AlternateDualProjectionCodec
        )
      )
  }

@Serializable
private data class StructuredValue(
  val label: String,
  val values: List<Int>
)

private class EncryptedCborSerializer : Serializer<StructuredValue> {
  override val defaultValue = StructuredValue("", emptyList())

  override suspend fun readFrom(input: InputStream): StructuredValue {
    val decrypted = input.readBytes().map { byte -> (byte.toInt() xor XOR_KEY).toByte() }.toByteArray()
    return StructuredSerializationCapture.decodeFromByteArray(
      Cbor.Default,
      StructuredValue.serializer(),
      decrypted
    )
  }

  override suspend fun writeTo(
    t: StructuredValue,
    output: OutputStream
  ) {
    val encoded =
      StructuredSerializationCapture.encodeToByteArray(
        Cbor.Default,
        StructuredValue.serializer(),
        t
      )
    output.write(encoded.map { byte -> (byte.toInt() xor XOR_KEY).toByte() }.toByteArray())
  }

  private companion object {
    const val XOR_KEY: Int = 0x5a
  }
}

private data class ContextToken(
  val value: String
)

private object ContextTokenSerializer : KSerializer<ContextToken> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("ContextToken", PrimitiveKind.STRING)

  override fun serialize(
    encoder: Encoder,
    value: ContextToken
  ) {
    encoder.encodeString(value.value)
  }

  override fun deserialize(decoder: Decoder): ContextToken = ContextToken(decoder.decodeString())
}

@Serializable
private data class ContextualValue(
  @Contextual val token: ContextToken
)

private class ContextualCborSerializer : Serializer<ContextualValue> {
  private val module =
    SerializersModule {
      contextual(ContextToken::class, ContextTokenSerializer)
    }
  private val format = Cbor { serializersModule = module }
  override val defaultValue = ContextualValue(ContextToken(""))

  override suspend fun readFrom(input: InputStream): ContextualValue =
    StructuredSerializationCapture.decodeFromByteArray(
      format,
      ContextualValue.serializer(),
      input.readBytes()
    )

  override suspend fun writeTo(
    t: ContextualValue,
    output: OutputStream
  ) {
    output.write(
      StructuredSerializationCapture.encodeToByteArray(
        format,
        ContextualValue.serializer(),
        t
      )
    )
  }
}

@Serializable
private data class GeneratedValue(
  val label: String,
  val counter: Int
)

private object GeneratedBinarySerializer : Serializer<GeneratedValue> {
  override val defaultValue = GeneratedValue("", 0)

  override suspend fun readFrom(input: InputStream): GeneratedValue =
    DataInputStream(input).use { data ->
      GeneratedValue(data.readUTF(), data.readInt())
    }

  override suspend fun writeTo(
    t: GeneratedValue,
    output: OutputStream
  ) {
    DataOutputStream(output).use { data ->
      data.writeUTF(t.label)
      data.writeInt(t.counter)
    }
  }
}

private data class TextValue(
  val document: String
)

private object TextSerializer : Serializer<TextValue> {
  override val defaultValue = TextValue("")

  override suspend fun readFrom(input: InputStream): TextValue = TextValue(input.readBytes().decodeToString())

  override suspend fun writeTo(
    t: TextValue,
    output: OutputStream
  ) {
    output.write(t.document.encodeToByteArray())
  }
}

private data class LossyValue(
  val visible: String,
  val hidden: String
)

private object LossySerializer : Serializer<LossyValue> {
  override val defaultValue = LossyValue("", "")

  override suspend fun readFrom(input: InputStream): LossyValue = LossyValue(input.readBytes().decodeToString(), "")

  override suspend fun writeTo(
    t: LossyValue,
    output: OutputStream
  ) {
    output.write(t.visible.encodeToByteArray())
  }
}

private object DuplicateJsonSerializer : Serializer<LossyValue> {
  override val defaultValue = LossyValue("", "")

  override suspend fun readFrom(input: InputStream): LossyValue = LossyValue("same", "state")

  override suspend fun writeTo(
    t: LossyValue,
    output: OutputStream
  ) {
    output.write("""{"same":1,"same":2}""".encodeToByteArray())
  }
}

private class NonDeterministicTextSerializer : Serializer<TextValue> {
  private var writes = 0
  override val defaultValue = TextValue("")

  override suspend fun readFrom(input: InputStream): TextValue = TextValue(input.readBytes().decodeToString().substringBefore('#'))

  override suspend fun writeTo(
    t: TextValue,
    output: OutputStream
  ) {
    writes += 1
    output.write("${t.document}#$writes".encodeToByteArray())
  }
}

@JvmInline
@Serializable
private value class InlineRoot(
  val value: String
)

private object InlineRootCborSerializer : Serializer<InlineRoot> {
  override val defaultValue: InlineRoot = InlineRoot("")

  override suspend fun readFrom(input: InputStream): InlineRoot =
    StructuredSerializationCapture.decodeFromByteArray(
      Cbor.Default,
      InlineRoot.serializer(),
      input.readBytes()
    )

  override suspend fun writeTo(
    t: InlineRoot,
    output: OutputStream
  ) {
    output.write(
      StructuredSerializationCapture.encodeToByteArray(
        Cbor.Default,
        InlineRoot.serializer(),
        t
      )
    )
  }
}

@Serializable
private data class EqualNormalRoot(
  val value: String
)

private object EqualButDistinctNestedSerializer : Serializer<EqualNormalRoot> {
  override val defaultValue: EqualNormalRoot = EqualNormalRoot("")

  override suspend fun readFrom(input: InputStream): EqualNormalRoot =
    Cbor.Default.decodeFromByteArray(
      EqualNormalRoot.serializer(),
      input.readBytes()
    )

  override suspend fun writeTo(
    t: EqualNormalRoot,
    output: OutputStream
  ) {
    output.write(
      StructuredSerializationCapture.encodeToByteArray(
        Cbor.Default,
        EqualNormalRoot.serializer(),
        t.copy()
      )
    )
  }
}

private class DynamicContractSerializer : Serializer<EqualNormalRoot> {
  override val defaultValue: EqualNormalRoot = EqualNormalRoot("")

  override suspend fun readFrom(input: InputStream): EqualNormalRoot =
    Cbor.Default.decodeFromByteArray(
      EqualNormalRoot.serializer(),
      input.readBytes()
    )

  override suspend fun writeTo(
    t: EqualNormalRoot,
    output: OutputStream
  ) {
    output.write(
      StructuredSerializationCapture.encodeToByteArray(
        Cbor.Default,
        DelegatingEqualNormalSerializer(),
        t
      )
    )
  }
}

private data class ProjectionControlFlow(
  val wrapper: Throwable,
  val original: Throwable
)

private enum class ProjectionControlFlowMode(
  val displayName: String
) {
  CANCELLATION("CancellationException"),
  FATAL("LinkageError")
  ;

  fun create(): ProjectionControlFlow {
    val original =
      when (this) {
        CANCELLATION -> CancellationException("projection cancelled")
        FATAL -> LinkageError("projection linkage failure")
      }
    return ProjectionControlFlow(
      wrapper = IOException("projection wrapper", original),
      original = original
    )
  }
}

private class InitialControlFlowSerializer(
  private val failure: Throwable
) : Serializer<TextValue> {
  override val defaultValue: TextValue = TextValue("")

  override suspend fun readFrom(input: InputStream): TextValue =
    TextValue(input.readBytes().decodeToString())

  override suspend fun writeTo(
    t: TextValue,
    output: OutputStream
  ) {
    throw failure
  }
}

@Serializable
private data class CachedControlFlowValue(
  val value: String
)

private class CachedControlFlowSerializer : Serializer<CachedControlFlowValue> {
  var failure: Throwable? = null
  override val defaultValue: CachedControlFlowValue = CachedControlFlowValue("")

  override suspend fun readFrom(input: InputStream): CachedControlFlowValue =
    StructuredSerializationCapture.decodeFromByteArray(
      Cbor.Default,
      CachedControlFlowValue.serializer(),
      input.readBytes()
    )

  override suspend fun writeTo(
    t: CachedControlFlowValue,
    output: OutputStream
  ) {
    failure?.let { throw it }
    output.write(
      StructuredSerializationCapture.encodeToByteArray(
        Cbor.Default,
        CachedControlFlowValue.serializer(),
        t
      )
    )
  }
}

private class DelegatingEqualNormalSerializer :
  KSerializer<EqualNormalRoot> by
  EqualNormalRoot.serializer()

@Serializable
private data class CaptureRoot(
  val value: String
)

private class DelegatingCaptureSerializer :
  KSerializer<CaptureRoot> by
  CaptureRoot.serializer()

private class FixedStringFormat(
  private val decodedValue: (() -> Any)? = null
) : StringFormat {
  override val serializersModule: SerializersModule = EmptySerializersModule()

  override fun <T> encodeToString(
    serializer: SerializationStrategy<T>,
    value: T
  ): String = "encoded"

  @Suppress("UNCHECKED_CAST")
  override fun <T> decodeFromString(
    deserializer: DeserializationStrategy<T>,
    string: String
  ): T = requireNotNull(decodedValue).invoke() as T
}
