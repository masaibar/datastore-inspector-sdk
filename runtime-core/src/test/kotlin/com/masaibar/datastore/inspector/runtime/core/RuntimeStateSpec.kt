package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ClearPreferences
import com.masaibar.datastore.inspector.protocol.PreferenceEntry
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.ReplacePreferences
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.protocol.WritePayload
import com.masaibar.datastore.inspector.protocol.WriteSuccess
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.concurrent.thread

class RuntimeStateSpec : DescribeSpec() {
  init {
    describe("RuntimeState") {
      context("既存の契約を検証するとき") {
        it("同じinstanceをidentityで1件にし同値の別instanceは分離する") {
          val registry = DataStoreRegistry(sequenceOf("one", "two", "three").iterator()::next)
          val factory = FakeFactory()
          val first = EqualCandidate(1)
          val equalButDifferent = EqualCandidate(1)
          val a = registry.resolve(first, declaration("a"), listOf(factory))
          val duplicate = registry.resolve(first, declaration("duplicate"), listOf(factory))
          val b = registry.resolve(equalButDifferent, declaration("b"), listOf(factory))

          (duplicate.storeId) shouldBe (a.storeId)
          (b.storeId) shouldNotBe (a.storeId)
          (registry.entries().size) shouldBe (2)
        }

        it("同じinstanceを別宣言でresolveしたとき未解決宣言を残さない") {
          val registry = DataStoreRegistry(sequenceOf("resolved", "orphan").iterator()::next)
          val instance = Any()
          val resolved = registry.resolve(instance, declaration("resolved"), listOf(FakeFactory()))
          registry.declare(declaration("orphan"))

          val duplicate = registry.resolve(instance, declaration("orphan"), listOf(FakeFactory()))

          (duplicate) shouldBeSameInstanceAs (resolved)
          (registry.entries().map { it.declaration.declarationId }) shouldBe (listOf("resolved"))
        }

        it("並行resolveでも同じinstanceは一度だけ分類する") {
          val registry = DataStoreRegistry()
          val factory = FakeFactory()
          val instance = Any()
          val results = java.util.Collections.synchronizedList(mutableListOf<RegistryEntry>())
          val workers = List(16) { index ->
            thread { results += registry.resolve(instance, declaration("d$index"), listOf(factory)) }
          }
          workers.forEach(Thread::join)

          (registry.entries().size) shouldBe (1)
          (factory.created) shouldBe (1)
          (results.map { it.storeId }.distinct().size) shouldBe (1)
        }

        it("fallback登録は利用者が保持する同じinstanceをidentityで重複排除する") {
          val instance = Any()
          val firstDeclaration = declaration("fallback-${System.nanoTime()}")
          val secondDeclaration = declaration("fallback-duplicate-${System.nanoTime()}")

          val first = DataStoreInspectorRuntime.registerFallback(instance, firstDeclaration)
          val duplicate = DataStoreInspectorRuntime.registerFallback(instance, secondDeclaration)

          (duplicate.storeId) shouldBe (first.storeId)
          (duplicate.declaration.declarationId) shouldBe (firstDeclaration.declarationId)
        }

        it("tokenはStoreに束縛され不一致でも消費する") {
          val cache = SnapshotLeaseCache(tokenFactory = { "store-bound" })
          val lease = cache.issue("store", "fingerprint")

          (cache.claim("other", lease.revision, lease.token)).shouldBeNull()
          (cache.size()) shouldBe (0)
        }

        it("tokenはrevisionに束縛され不一致でも消費する") {
          val cache = SnapshotLeaseCache(tokenFactory = { "revision-bound" })
          val lease = cache.issue("store", "fingerprint")

          (cache.claim("store", lease.revision + 1, lease.token)).shouldBeNull()
          (cache.size()) shouldBe (0)
        }

        it("tokenは正しいclaimでfingerprintを返し再利用を拒否する") {
          val cache = SnapshotLeaseCache(tokenFactory = { "single-use" })
          val lease = cache.issue("store", "fingerprint")

          (cache.claim("store", lease.revision, lease.token)) shouldBe ("fingerprint")
          (cache.claim("store", lease.revision, lease.token)).shouldBeNull()
        }

        it("同じStore世代とfingerprintの未消費leaseを再利用する") {
          val tokens = sequenceOf("stable", "unused").iterator()
          val cache = SnapshotLeaseCache(tokenFactory = tokens::next)

          val first = cache.issue("store", "fingerprint", storeGeneration = 4)
          val second = cache.issue("store", "fingerprint", storeGeneration = 4)

          (second) shouldBe (first)
          (cache.size()) shouldBe (1)
        }

        it("fingerprintまたはStore世代が変われば新しいleaseを発行する") {
          val tokens = sequenceOf("original", "changed-content", "changed-generation").iterator()
          val cache = SnapshotLeaseCache(tokenFactory = tokens::next)

          val original = cache.issue("store", "fingerprint", storeGeneration = 4)
          val changedContent = cache.issue("store", "updated", storeGeneration = 4)
          val changedGeneration = cache.issue("store", "fingerprint", storeGeneration = 5)

          (changedContent) shouldNotBe (original)
          (changedGeneration) shouldNotBe (original)
          (changedGeneration) shouldNotBe (changedContent)
          (cache.size()) shouldBe (3)
        }

        it("claimで消費した同一内容には新しいleaseを発行する") {
          val tokens = sequenceOf("consumed", "replacement").iterator()
          val cache = SnapshotLeaseCache(tokenFactory = tokens::next)
          val consumed = cache.issue("store", "fingerprint", storeGeneration = 4)
          (cache.claim("store", consumed.revision, consumed.token, storeGeneration = 4)) shouldBe ("fingerprint")

          val replacement = cache.issue("store", "fingerprint", storeGeneration = 4)

          (replacement) shouldNotBe (consumed)
          (cache.size()) shouldBe (1)
        }

        it("期限切れtokenを拒否する") {
          var now = 1_000L
          val cache = SnapshotLeaseCache(
            nowMillis = { now }, tokenFactory = { "expiring" }, ttlMillis = 100
          )
          val lease = cache.issue("store", "fingerprint")
          now += 101
          (cache.claim("store", lease.revision, lease.token)).shouldBeNull()
        }

        it("複数recordがあっても再利用で期限が延長されず期限切れtokenを拒否する") {
          var now = 1_000L
          val tokens = sequenceOf("oldest", "newer", "replacement").iterator()
          val cache = SnapshotLeaseCache(
            nowMillis = { now }, tokenFactory = tokens::next, ttlMillis = 100
          )
          val oldest = cache.issue("oldest-store", "oldest-fingerprint")
          now += 10
          val newer = cache.issue("newer-store", "newer-fingerprint")
          now += 10

          (cache.issue("oldest-store", "oldest-fingerprint")) shouldBe (oldest)
          now += 85

          (cache.claim("oldest-store", oldest.revision, oldest.token)).shouldBeNull()
          (cache.claim("newer-store", newer.revision, newer.token)) shouldBe ("newer-fingerprint")
          (cache.issue("oldest-store", "oldest-fingerprint")) shouldNotBe (oldest)
        }

        it("期限切れの同一内容には新しいleaseを発行する") {
          var now = 1_000L
          val tokens = sequenceOf("expired", "replacement").iterator()
          val cache = SnapshotLeaseCache(
            nowMillis = { now }, tokenFactory = tokens::next, ttlMillis = 100
          )
          val expired = cache.issue("store", "fingerprint")
          now += 101

          val replacement = cache.issue("store", "fingerprint")

          (replacement) shouldNotBe (expired)
          (cache.size()) shouldBe (1)
        }

        it("同一内容のsnapshotはleaseを再利用しwrite後のtoken再利用を拒否する") {
          val registry = DataStoreRegistry { "store" }
          registry.resolve(Any(), declaration("a"), listOf(FakeFactory()))
          val service = RuntimeStoreService(registry)
          val first = (service.snapshot("store")).shouldBeInstanceOf<ResolvedSnapshotResult>().snapshot
          val second = (service.snapshot("store")).shouldBeInstanceOf<ResolvedSnapshotResult>().snapshot
          (second.revision) shouldBe (first.revision)
          (second.contentToken) shouldBe (first.contentToken)

          val write = WritePayload("store", second.revision, second.contentToken, ClearPreferences)
          (service.write(write)).shouldBeInstanceOf<WriteSuccess>()
          (runCatching { service.write(write) }.exceptionOrNull()).shouldBeInstanceOf<RuntimeStoreException.Stale>()
        }

        it("Preferences全置換は専用capability未交渉ならtokenを消費せず拒否する") {
          val registry = DataStoreRegistry { "store" }
          registry.resolve(Any(), declaration("a"), listOf(FakeFactory()))
          val service = RuntimeStoreService(registry)
          val legacyContext =
            RuntimeConnectionContext(
              version = ProtocolVersion(1, 2),
              capabilities =
                ProtocolCapabilities.INITIAL -
                  ProtocolCapabilities.PREFERENCES_REPLACE,
              sessionId = "legacy"
            )
          val snapshot =
            (service.snapshot("store", legacyContext)).shouldBeInstanceOf<ResolvedSnapshotResult>().snapshot
          val write =
            WritePayload(
              "store",
              snapshot.revision,
              snapshot.contentToken,
              ReplacePreferences(
                listOf(PreferenceEntry("value", StringValue("restored")))
              )
            )

          (runCatching { service.write(write, legacyContext) }.exceptionOrNull()).shouldBeInstanceOf<RuntimeStoreException.Capability>()
          (service.write(write)).shouldBeInstanceOf<WriteSuccess>()
        }
      }
    }
  }

  private fun declaration(id: String) = StoreDeclaration(id, id, null, StoreKind.PREFERENCES, "owner", id)

  private data class EqualCandidate(val value: Int)

  private class FakeFactory : StoreAdapterFactory {
    override val providerId = "fake"
    var created = 0

    override fun create(candidate: StoreCandidate): AdapterResolution {
      created += 1
      return AdapterResolution.Resolved(FakeAdapter)
    }
  }

  private object FakeAdapter : StoreAdapter {
    override val kind = StoreKind.PREFERENCES
    override val capabilities =
      setOf(
        com.masaibar.datastore.inspector.protocol.StoreCapability(
          ProtocolCapabilities.PREFERENCES_WRITE
        ),
        com.masaibar.datastore.inspector.protocol.StoreCapability(
          ProtocolCapabilities.PREFERENCES_REPLACE
        ),
        com.masaibar.datastore.inspector.protocol.StoreCapability(
          ProtocolCapabilities.STORE_RESET
        )
      )
    override val schema = null
    override suspend fun snapshot() = AdapterSnapshot("fingerprint", emptyTree())
    override suspend fun write(expectedFingerprint: String, operation: com.masaibar.datastore.inspector.protocol.WriteOperation) =
      AdapterWriteResult.Applied(AdapterSnapshot("updated", emptyTree()))

    private fun emptyTree() = PreferencesTree(
      com.masaibar.datastore.inspector.protocol.InspectorNode(
        emptyList(), "root", com.masaibar.datastore.inspector.protocol.InspectorValueType.ROOT,
        null, com.masaibar.datastore.inspector.protocol.Presence.NOT_APPLICABLE,
        emptyList(), emptySet()
      )
    )
  }
}
