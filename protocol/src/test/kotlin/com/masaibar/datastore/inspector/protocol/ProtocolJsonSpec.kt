package com.masaibar.datastore.inspector.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ProtocolJsonSpec : DescribeSpec() {
  private val schema =
    ProtoSchemaRef(
      schemaId = "schema-1",
      rootMessageFullName = "sample.UserSettings",
      descriptorDigestSha256 = "a".repeat(64)
    )

  init {
    describe("ProtocolJson") {
      context("既存の契約を検証するとき") {
        it("全request subtypeをround tripできる") {
          val requests =
            listOf(
              HandshakeRequest(
                version = ProtocolVersion(1, 0),
                capabilities = ProtocolCapabilities.INITIAL,
                sessionId = "session",
                sessionToken = "secret"
              ),
              ListStoresRequest,
              GetSnapshotRequest("preferences-main"),
              GetSchemaRequest("schema-1"),
              WriteRequest(
                WritePayload(
                  storeId = "preferences-main",
                  expectedRevision = 3,
                  expectedContentToken = "opaque-token",
                  operation = MutatePreferences(PutPreference("counter", IntValue(4)))
                )
              ),
              WriteRequest(
                WritePayload(
                  storeId = "proto-main",
                  expectedRevision = 2,
                  expectedContentToken = "opaque-token-2",
                  operation = ReplaceProtoBytes(schema, byteArrayOf(1, 2, 3))
                )
              ),
              WriteRequest(
                WritePayload(
                  storeId = "preferences-main",
                  expectedRevision = 4,
                  expectedContentToken = "opaque-token-3",
                  operation =
                    ReplacePreferences(
                      listOf(
                        PreferenceEntry("count", IntValue(4)),
                        PreferenceEntry("name", StringValue("Ada"))
                      )
                    )
                )
              ),
              WriteRequest(WritePayload("preferences-main", 4, "token-3", ClearPreferences)),
              WriteRequest(WritePayload("proto-main", 4, "token-4", ResetStore))
            )

          requests.forEachIndexed { index, payload ->
            val expected = RequestEnvelope("request-$index", payload)
            (ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(expected))) shouldBe (expected)
          }
        }

        it("response unionとBase64 byte列をround tripできる") {
          val proto = ProtoRaw(schema, byteArrayOf(0, 1, 2, -1))
          val snapshot = ResolvedStoreSnapshot("proto-main", 8, "opaque", proto)
          val responses =
            listOf<ResponsePayload>(
              HandshakeResponse(ProtocolVersion(1, 0), ProtocolCapabilities.INITIAL, "session"),
              ListStoresResult(
                listOf(
                  StoreDescriptor(
                    "proto-main",
                    "Proto main",
                    "settings.pb",
                    StoreKind.PROTO,
                    StoreStatus.RESOLVED,
                    setOf(StoreCapability(ProtocolCapabilities.SNAPSHOT_GET)),
                    schema
                  )
                )
              ),
              SnapshotResultResponse(ResolvedSnapshotResult(snapshot)),
              SnapshotResultResponse(
                UnsupportedSnapshotInfo(
                  "custom-main",
                  UnsupportedReason("custom_serializer", "未対応serializerです。", false)
                )
              ),
              GetSchemaResult("schema-1", "a".repeat(64), byteArrayOf(9, 8, 7)),
              WriteResultResponse(WriteSuccess(snapshot)),
              WriteResultResponse(WriteConflict(snapshot)),
              ErrorResponse(ProtocolErrorCode.STALE_SNAPSHOT, "再取得してください。", true)
            )

          responses.forEachIndexed { index, payload ->
            val expected = ResponseEnvelope("response-$index", payload)
            val encoded = ProtocolJson.encodeResponse(expected)
            val actual = ProtocolJson.decodeResponse(encoded)
            (actual) shouldBe (expected)
            (encoded.decodeToString().contains("\"type\"")) shouldBe true
          }
          (proto.valueBytes).toList() shouldBe (byteArrayOf(0, 1, 2, -1)).toList()
        }

        it("未知fieldを許容し必須field欠落と未知subtypeを拒否する") {
          val withFuture =
            """{"requestId":"r","future":true,"payload":{"type":"get_snapshot","storeId":"s","newField":1}}"""
          (ProtocolJson.decodeRequestString(withFuture)) shouldBe (RequestEnvelope("r", GetSnapshotRequest("s")))

          shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequestString("""{"payload":{"type":"list_stores"}}""")
          }
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequestString("""{"requestId":"r","payload":{"type":"future_request"}}""")
          }
        }

        it("不正UTF8と非canonical Base64をtyped errorとして拒否する") {
          val utf8Error = shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequest(byteArrayOf(0xC3.toByte(), 0x28))
          }
          (utf8Error.kind) shouldBe (ProtocolFailureKind.MALFORMED_UTF8)

          val invalidBase64 =
            """{"requestId":"r","payload":{"type":"write","write":{"storeId":"s","expectedRevision":1,"expectedContentToken":"t","operation":{"type":"replace_proto_bytes","schema":{"schemaId":"id","rootMessageFullName":"sample.Root","descriptorDigestSha256":"${"a".repeat(64)}"},"valueBytes":"AQI"}}}}"""
          val base64Error = shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequestString(invalidBase64)
          }
          (base64Error.kind) shouldBe (ProtocolFailureKind.MALFORMED_BASE64)
        }

        it("未知Store kindとstatusをUNKNOWNへfallbackする") {
          val payload =
            """{"requestId":"r","payload":{"type":"stores_listed","stores":[{"id":"s","name":"future","fileName":null,"kind":"future_kind","status":"future_status","capabilities":[],"schema":null,"unsupportedReason":null}]}}"""
          val result = ProtocolJson.decodeResponseString(payload).payload as ListStoresResult
          (result.stores.single().kind) shouldBe (StoreKind.UNKNOWN)
          (result.stores.single().status) shouldBe (StoreStatus.UNKNOWN)

          val unsafe =
            ResponseEnvelope(
              "unsafe",
              ListStoresResult(
                listOf(
                  StoreDescriptor(
                    id = "future",
                    name = "future",
                    fileName = null,
                    kind = StoreKind.UNKNOWN,
                    status = StoreStatus.UNKNOWN,
                    capabilities =
                      setOf(StoreCapability(ProtocolCapabilities.STORE_RESET))
                  )
                )
              )
            )
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(unsafe))
          }
        }

        it("Preferences treeのtype不一致と不正な値表現を拒否する") {
          val badLeaf =
            InspectorNode(
              path = listOf(PreferenceKey("answer")),
              name = "answer",
              type = InspectorValueType.STRING,
              value = IntValue(42),
              presence = Presence.PRESENT,
              children = emptyList(),
              capabilities = emptySet()
            )
          val root =
            InspectorNode(
              path = emptyList(),
              name = "root",
              type = InspectorValueType.ROOT,
              value = null,
              presence = Presence.NOT_APPLICABLE,
              children = listOf(badLeaf),
              capabilities = emptySet()
            )
          val response =
            ResponseEnvelope(
              "r",
              SnapshotResultResponse(
                ResolvedSnapshotResult(
                  ResolvedStoreSnapshot("s", 1, "token", PreferencesTree(root))
                )
              )
            )
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response))
          }

          val invalidSet =
            RequestEnvelope(
              "r2",
              WriteRequest(
                WritePayload(
                  "s",
                  1,
                  "token",
                  MutatePreferences(PutPreference("set", StringSetValue(listOf("z", "a"))))
                )
              )
            )
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(invalidSet))
          }
        }

        it("Preferences全value subtypeを損失なくround tripできる") {
          val values =
            listOf(
              InspectorValueType.STRING to StringValue("日本語"),
              InspectorValueType.INT to IntValue(Int.MIN_VALUE),
              InspectorValueType.LONG to LongValue(Long.MAX_VALUE),
              InspectorValueType.FLOAT to FloatValue("80000000"),
              InspectorValueType.DOUBLE to DoubleValue("7ff8000000000000"),
              InspectorValueType.BOOLEAN to BooleanValue(true),
              InspectorValueType.STRING_SET to StringSetValue(listOf("alpha", "beta")),
              InspectorValueType.BYTES to BytesValue(byteArrayOf(0, 1, -1))
            )
          val children =
            values.mapIndexed { index, (type, value) ->
              val key = "key-$index"
              InspectorNode(
                path = listOf(PreferenceKey(key)),
                name = key,
                type = type,
                value = value,
                presence = Presence.PRESENT,
                children = emptyList(),
                capabilities = setOf(NodeCapability("edit"))
              )
            }
          val root =
            InspectorNode(
              path = emptyList(),
              name = "root",
              type = InspectorValueType.ROOT,
              value = null,
              presence = Presence.NOT_APPLICABLE,
              children = children,
              capabilities = setOf(NodeCapability("clear"))
            )
          val expected =
            ResponseEnvelope(
              "all-values",
              SnapshotResultResponse(
                ResolvedSnapshotResult(
                  ResolvedStoreSnapshot("preferences", 1, "token", PreferencesTree(root))
                )
              )
            )

          (ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(expected))) shouldBe (expected)
        }

        it("Preferences全置換をcanonical entry列としてround tripする") {
          val entries =
            listOf(
              PreferenceEntry("boolean", BooleanValue(true)),
              PreferenceEntry("bytes", BytesValue(byteArrayOf(0, 1, -1))),
              PreferenceEntry("double", DoubleValue("7ff8000000000000")),
              PreferenceEntry("float", FloatValue("80000000")),
              PreferenceEntry("int", IntValue(Int.MIN_VALUE)),
              PreferenceEntry("long", LongValue(Long.MAX_VALUE)),
              PreferenceEntry("string", StringValue("日本語")),
              PreferenceEntry("string_set", StringSetValue(listOf("alpha", "beta")))
            )
          val expected =
            RequestEnvelope(
              "replace-preferences",
              WriteRequest(
                WritePayload(
                  storeId = "preferences",
                  expectedRevision = 1,
                  expectedContentToken = "token",
                  operation = ReplacePreferences(entries)
                )
              )
            )

          (ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(expected))) shouldBe (expected)
        }

        it("Preferences全置換の重複keyと非canonical順と件数超過を拒否する") {
          val invalidEntries =
            listOf(
              listOf(
                PreferenceEntry("same", IntValue(1)),
                PreferenceEntry("same", IntValue(2))
              ),
              listOf(
                PreferenceEntry("z", IntValue(1)),
                PreferenceEntry("a", IntValue(2))
              ),
              List(ProtocolLimits.MAX_PREFERENCES_ENTRIES + 1) { index ->
                PreferenceEntry("key-%05d".format(index), IntValue(index))
              }
            )

          invalidEntries.forEachIndexed { index, entries ->
            val request =
              RequestEnvelope(
                "invalid-replacement-$index",
                WriteRequest(
                  WritePayload("preferences", 1, "token", ReplacePreferences(entries))
                )
              )
            shouldThrow<ProtocolException> {
              ProtocolJson.encodeRequest(request)
            }
          }
        }

        it("Protocol 1_0 literal descriptorをlegacy semanticsとしてdecodeする") {
          val literal =
            """
            {
              "requestId":"legacy",
              "payload":{
                "type":"stores_listed",
                "stores":[{
                  "id":"preferences",
                  "name":"preferences",
                  "fileName":"preferences.preferences_pb",
                  "kind":"preferences",
                  "status":"resolved",
                  "capabilities":["snapshot.get"],
                  "schema":null,
                  "unsupportedReason":null
                }]
              }
            }
            """.trimIndent()

          val descriptor =
            ((ProtocolJson.decodeResponseString(literal).payload).shouldBeInstanceOf<ListStoresResult>()).stores.single()

          (descriptor.semantics) shouldBe (null)
          (descriptor.kind) shouldBe (StoreKind.PREFERENCES)
        }

        it("Preferences全置換capabilityはknown semanticsのresolved Preferencesだけが公開できる") {
          val valid =
            StoreDescriptor(
              id = "preferences",
              name = "preferences",
              fileName = "preferences.preferences_pb",
              kind = StoreKind.PREFERENCES,
              status = StoreStatus.RESOLVED,
              capabilities =
                setOf(
                  StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
                  StoreCapability(ProtocolCapabilities.PREFERENCES_WRITE),
                  StoreCapability(ProtocolCapabilities.PREFERENCES_REPLACE)
                ),
              semantics =
                StoreSemantics(
                  backend = StoreBackend.DATASTORE,
                  storageScope = StorageScope.CREDENTIAL_PROTECTED,
                  supportedValueTypes = PreferenceValueTypeIds.DATASTORE,
                  writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
                )
            )
          val response = ResponseEnvelope("valid-replace", ListStoresResult(listOf(valid)))

          (ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response))) shouldBe (response)

          val invalidDescriptors =
            listOf(
              valid.copy(kind = StoreKind.PROTO),
              valid.copy(semantics = null),
              valid.copy(
                semantics =
                  valid.semantics?.copy(
                    writeConsistency = WriteConsistency.UNKNOWN
                  )
              )
            )
          invalidDescriptors.forEachIndexed { index, descriptor ->
            shouldThrow<ProtocolException> {
              ProtocolJson.encodeResponse(
                ResponseEnvelope("invalid-replace-$index", ListStoresResult(listOf(descriptor)))
              )
            }
          }
        }

        it("未知semantics enumとvalue type IDを保持しwrite capabilityを安全側で拒否する") {
          val literal =
            """
            {
              "requestId":"future",
              "payload":{
                "type":"stores_listed",
                "stores":[{
                  "id":"future",
                  "name":"future",
                  "fileName":null,
                  "kind":"preferences",
                  "status":"resolved",
                  "capabilities":["snapshot.get"],
                  "schema":null,
                  "unsupportedReason":null,
                  "semantics":{
                    "backend":"future_backend",
                    "storageScope":"future_scope",
                    "supportedValueTypes":["string","future_value"],
                    "writeConsistency":"future_consistency"
                  }
                }]
              }
            }
            """.trimIndent()
          val descriptor =
            ((ProtocolJson.decodeResponseString(literal).payload).shouldBeInstanceOf<ListStoresResult>()).stores.single()

          (descriptor.semantics?.backend) shouldBe (StoreBackend.UNKNOWN)
          (descriptor.semantics?.storageScope) shouldBe (StorageScope.UNKNOWN)
          (descriptor.semantics?.writeConsistency) shouldBe (WriteConsistency.UNKNOWN)
          (descriptor.semantics?.supportedValueTypes) shouldBe (setOf("string", "future_value"))

          val unsafe = literal.replace(
            """"snapshot.get"""",
            """"preferences.write""""
          )
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeResponseString(unsafe)
          }
        }

        it("Protocol 1_1 write outcomeとoperationStartedをround tripする") {
          val outcomes =
            listOf<ResponsePayload>(
              WriteResultResponse(WriteAppliedSnapshotUnavailable("preferences")),
              WriteResultResponse(
                WriteOutcomeUnknown(
                  reason = WriteOutcomeReason.PERSISTENCE_NOT_CONFIRMED
                )
              ),
              ErrorResponse(
                code = ProtocolErrorCode.BUSY,
                safeMessage = "処理中です。",
                retryable = true,
                operationStarted = false
              )
            )

          outcomes.forEachIndexed { index, payload ->
            val expected = ResponseEnvelope("outcome-$index", payload)
            (ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(expected))) shouldBe (expected)
          }
        }

        it("空文字と空白だけのpreference keyを欠落なくround tripする") {
          listOf("", "   ").forEachIndexed { index, key ->
            val request =
              RequestEnvelope(
                "empty-key-$index",
                WriteRequest(
                  WritePayload(
                    "preferences",
                    1,
                    "token",
                    MutatePreferences(PutPreference(key, StringValue("value")))
                  )
                )
              )

            (ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(request))) shouldBe (request)
          }
        }

        it("supported value type IDの件数文字種とbyte上限を拒否する") {
          val invalidSets =
            listOf(
              (0..ProtocolLimits.MAX_SUPPORTED_VALUE_TYPES).map { "type-$it" }.toSet(),
              setOf("x".repeat(ProtocolLimits.MAX_SUPPORTED_VALUE_TYPE_ID_UTF8_BYTES + 1)),
              setOf("日本語"),
              setOf("")
            )

          invalidSets.forEachIndexed { index, supportedTypes ->
            val response =
              ResponseEnvelope(
                "invalid-types-$index",
                ListStoresResult(
                  listOf(
                    StoreDescriptor(
                      id = "store",
                      name = "store",
                      fileName = null,
                      kind = StoreKind.PREFERENCES,
                      status = StoreStatus.RESOLVED,
                      capabilities =
                        setOf(
                          StoreCapability(ProtocolCapabilities.SNAPSHOT_GET)
                        ),
                      semantics =
                        StoreSemantics(
                          StoreBackend.SHARED_PREFERENCES,
                          StorageScope.CREDENTIAL_PROTECTED,
                          supportedTypes,
                          WriteConsistency.BEST_EFFORT_NON_ATOMIC
                        )
                    )
                  )
                )
              )

            shouldThrow<ProtocolException> {
              ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response))
            }
          }
        }
      }
    }
  }
}

class ProtocolFramingAndNegotiationSpec : DescribeSpec() {
  init {
    describe("ProtocolFramingAndNegotiation") {
      context("既存の契約を検証するとき") {
        it("big endian length frameを境界値で処理する") {
          val payload = "こんにちは".encodeToByteArray()
          val frame = ProtocolFraming.encode(payload, ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)
          (frame.copyOfRange(0, 4)).toList() shouldBe (byteArrayOf(0, 0, 0, payload.size.toByte())).toList()
          (ProtocolFraming.decode(frame, ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)).toList() shouldBe (payload).toList()

          val unsignedTooLarge = byteArrayOf(-1, -1, -1, -1)
          val error = shouldThrow<ProtocolException> {
            ProtocolFraming.decode(unsignedTooLarge, ProtocolLimits.AUTHENTICATED_FRAME_BYTES)
          }
          (error.kind) shouldBe (ProtocolFailureKind.PAYLOAD_TOO_LARGE)
        }

        it("major一致時は低いminorとcapability積集合を使う") {
          val negotiated =
            ProtocolNegotiation.negotiate(
              ProtocolVersion(1, 2),
              ProtocolCapabilities.INITIAL,
              ProtocolVersion(1, 0),
              setOf(
                ProtocolCapabilities.STORES_LIST,
                ProtocolCapabilities.SNAPSHOT_GET,
                ProtocolCapabilities.SCHEMA_GET,
                "future.capability"
              )
            )
          (negotiated.version) shouldBe (ProtocolVersion(1, 0))
          (negotiated.capabilities) shouldBe (
            setOf(
              ProtocolCapabilities.STORES_LIST,
              ProtocolCapabilities.SNAPSHOT_GET,
              ProtocolCapabilities.SCHEMA_GET
            )
          )
        }

        it("現行1_4と旧1_0はminor 1_0で従来capabilityだけを交渉する") {
          val legacyCapabilities =
            ProtocolCapabilities.INITIAL -
              ProtocolCapabilities.SHARED_PREFERENCES_INSPECT -
              ProtocolCapabilities.CUSTOM_DOCUMENT -
              ProtocolCapabilities.PREFERENCES_REPLACE -
              ProtocolCapabilities.STORE_CHANGES

          val negotiated =
            ProtocolNegotiation.negotiate(
              ProtocolVersion.CURRENT,
              ProtocolCapabilities.INITIAL,
              ProtocolVersion(1, 0),
              legacyCapabilities
            )

          (negotiated.version) shouldBe (ProtocolVersion(1, 0))
          (negotiated.capabilities) shouldBe (legacyCapabilities)
        }

        it("Protocol 1_2では双方が広告してもPreferences全置換を交渉しない") {
          val negotiated =
            ProtocolNegotiation.negotiate(
              ProtocolVersion.CURRENT,
              ProtocolCapabilities.INITIAL,
              ProtocolVersion(1, 2),
              ProtocolCapabilities.INITIAL
            )

          (negotiated.version) shouldBe (ProtocolVersion(1, 2))
          (negotiated.capabilities) shouldBe (
            ProtocolCapabilities.INITIAL -
              ProtocolCapabilities.PREFERENCES_REPLACE -
              ProtocolCapabilities.STORE_CHANGES
          )
        }

        it("Protocol 1_3ではPreferences全置換だけを追加交渉する") {
          val negotiated =
            ProtocolNegotiation.negotiate(
              ProtocolVersion.CURRENT,
              ProtocolCapabilities.INITIAL,
              ProtocolVersion(1, 3),
              ProtocolCapabilities.INITIAL
            )

          (negotiated.version) shouldBe (ProtocolVersion(1, 3))
          (ProtocolCapabilities.PREFERENCES_REPLACE in negotiated.capabilities) shouldBe true
          (ProtocolCapabilities.STORE_CHANGES !in negotiated.capabilities) shouldBe true
        }

        it("Protocol 1_4では双方が広告したStore change購読を交渉する") {
          val negotiated =
            ProtocolNegotiation.negotiate(
              ProtocolVersion.CURRENT,
              ProtocolCapabilities.INITIAL,
              ProtocolVersion.CURRENT,
              ProtocolCapabilities.INITIAL
            )

          (negotiated.version) shouldBe (ProtocolVersion(1, 4))
          (ProtocolCapabilities.STORE_CHANGES in negotiated.capabilities) shouldBe true
        }

        it("major不一致と必須capability不足を拒否する") {
          val majorError = shouldThrow<ProtocolException> {
            ProtocolNegotiation.negotiate(
              ProtocolVersion(1, 0),
              ProtocolCapabilities.INITIAL,
              ProtocolVersion(2, 0),
              ProtocolCapabilities.INITIAL
            )
          }
          (majorError.kind) shouldBe (ProtocolFailureKind.VERSION_MISMATCH)

          val capabilityError = shouldThrow<ProtocolException> {
            ProtocolNegotiation.negotiate(
              ProtocolVersion(1, 0),
              ProtocolCapabilities.INITIAL,
              ProtocolVersion(1, 0),
              setOf(ProtocolCapabilities.STORES_LIST)
            )
          }
          (capabilityError.kind) shouldBe (ProtocolFailureKind.MISSING_CAPABILITY)
        }

        it("JSON depth 64超過をdecode前に拒否する") {
          val deep = "[".repeat(65) + "]".repeat(65)
          val error = shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequestString(deep)
          }
          (error.kind) shouldBe (ProtocolFailureKind.MALFORMED_JSON)
        }

        it("複数payload長と切断frameを決定的に検証する") {
          val random = kotlin.random.Random(20260721)
          repeat(128) {
            val payload = random.nextBytes(random.nextInt(0, 4097))
            val frame = ProtocolFraming.encode(payload, 4096)
            (ProtocolFraming.decode(frame, 4096)).toList() shouldBe (payload).toList()
            if (frame.size > 4) {
              shouldThrow<ProtocolException> {
                ProtocolFraming.decode(frame.copyOf(frame.size - 1), 4096)
              }
            }
          }
        }
      }
    }
  }
}
