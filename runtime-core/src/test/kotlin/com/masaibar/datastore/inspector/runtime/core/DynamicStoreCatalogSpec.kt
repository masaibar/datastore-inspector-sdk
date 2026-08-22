package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.InspectorNode
import com.masaibar.datastore.inspector.protocol.InspectorValueType
import com.masaibar.datastore.inspector.protocol.PreferenceValueTypeIds
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.Presence
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class DynamicStoreCatalogSpec : DescribeSpec() {
  init {
    describe("DynamicStoreCatalog") {
      context("既存の契約を検証するとき") {
        it("connection contextはnegotiated capabilityをdefensive copyする") {
          val mutableCapabilities =
            mutableSetOf(
              ProtocolCapabilities.STORES_LIST,
              ProtocolCapabilities.SNAPSHOT_GET
            )
          val context =
            RuntimeConnectionContext(
              ProtocolVersion.CURRENT,
              mutableCapabilities,
              "session"
            )

          mutableCapabilities.clear()

          (context.capabilities) shouldBe (
            setOf(
              ProtocolCapabilities.STORES_LIST,
              ProtocolCapabilities.SNAPSHOT_GET
            )
          )
        }

        it("連続観測ではIDを維持し削除後の再作成ではIDとgenerationを更新する") {
          val leases = SnapshotLeaseCache(tokenFactory = sequenceOf("old", "new").iterator()::next)
          val provider = FakeCatalogProvider()
          provider.logicalNames = listOf("settings")
          val ids = sequenceOf("first-id", "second-id").iterator()
          val catalog = DynamicStoreCatalog(listOf(provider), leases, ids::next)

          val first = catalog.refresh(context(), PROCESS, emptyList()).single()
          val opened = first.adapter() as FakeCatalogAdapter
          val lease = leases.issue(first.storeId, "fingerprint", first.generation)
          val continuous = catalog.refresh(context(), PROCESS, emptyList()).single()
          (continuous) shouldBeSameInstanceAs (first)
          (continuous.storeId) shouldBe ("first-id")

          provider.logicalNames = emptyList()
          (catalog.refresh(context(), PROCESS, emptyList()).isEmpty()) shouldBe true
          (opened.closed) shouldBe true
          (leases.claim(first.storeId, lease.revision, lease.token, first.generation)).shouldBeNull()

          provider.logicalNames = listOf("settings")
          val recreated = catalog.refresh(context(), PROCESS, emptyList()).single()
          (recreated.storeId) shouldBe ("second-id")
          (recreated.generation) shouldNotBe (first.generation)
        }

        it("scan失敗では既存catalogを保持し部分更新やdisposeを行わない") {
          val provider = FakeCatalogProvider()
          provider.logicalNames = listOf("one")
          val catalog = DynamicStoreCatalog(listOf(provider), SnapshotLeaseCache()) { "stable" }
          val existing = catalog.refresh(context(), PROCESS, emptyList()).single()
          val adapter = existing.adapter() as FakeCatalogAdapter

          provider.logicalNames = listOf("one", "partial")
          provider.failure = StoreCatalogException(ProtocolErrorCode.STORE_ERROR, retryable = true)
          val error =
            shouldThrow<StoreCatalogException> {
              catalog.refresh(context(), PROCESS, emptyList())
            }

          (error.retryable) shouldBe true
          (catalog.entries(context())) shouldBe (listOf(existing))
          (adapter.closed) shouldBe false
        }

        it("incarnation token変更はadapter replacementとして旧leaseとadapterを破棄する") {
          val leases = SnapshotLeaseCache(tokenFactory = { "lease" })
          val provider = FakeCatalogProvider()
          provider.logicalNames = listOf("settings")
          val ids = sequenceOf("v1", "v2").iterator()
          val catalog = DynamicStoreCatalog(listOf(provider), leases, ids::next)
          val original = catalog.refresh(context(), PROCESS, emptyList()).single()
          val adapter = original.adapter() as FakeCatalogAdapter
          val lease = leases.issue(original.storeId, "fingerprint", original.generation)

          provider.incarnationToken = "replacement"
          val replacement = catalog.refresh(context(), PROCESS, emptyList()).single()

          (replacement.storeId) shouldBe ("v2")
          (adapter.closed) shouldBe true
          (leases.claim(original.storeId, lease.revision, lease.token, original.generation)).shouldBeNull()
        }

        it("capability未交渉時はproviderをscanせずStore ID推測も公開しない") {
          val provider = FakeCatalogProvider()
          provider.logicalNames = listOf("secret")
          val catalog = DynamicStoreCatalog(listOf(provider), SnapshotLeaseCache()) { "opaque-id" }
          val service =
            RuntimeStoreService(
              registry = DataStoreRegistry(),
              processName = PROCESS,
              catalog = catalog
            )
          val legacyContext =
            RuntimeConnectionContext(
              version = ProtocolVersion(1, 0),
              capabilities =
                setOf(
                  ProtocolCapabilities.STORES_LIST,
                  ProtocolCapabilities.SNAPSHOT_GET
                ),
              sessionId = "legacy"
            )

          (service.list(legacyContext).isEmpty()) shouldBe true
          (provider.scanCount) shouldBe (0)
          (runCatching { service.snapshot("opaque-id", legacyContext) }.exceptionOrNull()).shouldBeInstanceOf<RuntimeStoreException.NotFound>()

          val listed = service.list(context()).single()
          (listed.id) shouldBe ("opaque-id")
          (provider.scanCount) shouldBe (1)
          (service.snapshot(listed.id, context())).shouldBeInstanceOf<ResolvedSnapshotResult>()

          (service.list(legacyContext).isEmpty()) shouldBe true
          (provider.scanCount) shouldBe (1)
          (service.list(context()).single().id) shouldBe ("opaque-id")
        }

        it("provider ID重複とRegistry semantic identity衝突をstructured errorにする") {
          val duplicateA = FakeCatalogProvider(providerId = "duplicate")
          val duplicateB = FakeCatalogProvider(providerId = "duplicate")
          (
            shouldThrow<StoreCatalogException> {
              DynamicStoreCatalog(listOf(duplicateA, duplicateB), SnapshotLeaseCache())
            }.code
          ) shouldBe (ProtocolErrorCode.STORE_ERROR)

          val registry = DataStoreRegistry { "registry" }
          registry.resolve(
            Any(),
            StoreDeclaration(
              declarationId = "declared",
              name = "same",
              fileName = null,
              kindHint = StoreKind.PREFERENCES,
              owner = "owner",
              property = "property"
            ),
            listOf(DataStoreFactory)
          )
          val colliding = FakeCatalogProvider(backend = StoreBackend.DATASTORE)
          colliding.logicalNames = listOf("same")
          val catalog = DynamicStoreCatalog(listOf(colliding), SnapshotLeaseCache())

          (
            shouldThrow<StoreCatalogException> {
              catalog.refresh(context(), PROCESS, registry.entries())
            }.code
          ) shouldBe (ProtocolErrorCode.STORE_ERROR)
        }

        it("Registryとの合計Store上限を超えたcatalogを公開しない") {
          val provider = FakeCatalogProvider()
          provider.logicalNames =
            (0 until DynamicStoreCatalog.MAX_STORES).map { index -> "store-$index" }
          val catalog = DynamicStoreCatalog(listOf(provider), SnapshotLeaseCache())
          val registryEntry =
            RegistryEntry(
              storeId = "static",
              declaration =
                StoreDeclaration(
                  "static",
                  "static",
                  null,
                  StoreKind.CUSTOM,
                  "owner",
                  "property"
                ),
              state = RegistryState.Declared
            )

          val error =
            shouldThrow<StoreCatalogException> {
              catalog.refresh(context(), PROCESS, listOf(registryEntry))
            }

          (error.code) shouldBe (ProtocolErrorCode.STORE_CATALOG_LIMIT)
          (catalog.entries(context()).isEmpty()) shouldBe true
        }

        it("closeはopen済みadapterとproviderをdisposeしleaseをclearする") {
          val leases = SnapshotLeaseCache(tokenFactory = { "token" })
          val provider = FakeCatalogProvider()
          provider.logicalNames = listOf("settings")
          val catalog = DynamicStoreCatalog(listOf(provider), leases) { "id" }
          val record = catalog.refresh(context(), PROCESS, emptyList()).single()
          val adapter = record.adapter() as FakeCatalogAdapter
          leases.issue(record.storeId, "fingerprint", record.generation)

          catalog.close()

          (adapter.closed) shouldBe true
          (provider.closed) shouldBe true
          (catalog.entries(context()).isEmpty()) shouldBe true
          (leases.size()) shouldBe (0)
        }
      }
    }
  }

  private fun context(): RuntimeConnectionContext =
    RuntimeConnectionContext(
      ProtocolVersion.CURRENT,
      ProtocolCapabilities.INITIAL,
      "session"
    )

  private class FakeCatalogProvider(
    override val providerId: String = "catalog",
    private val backend: StoreBackend = StoreBackend.SHARED_PREFERENCES
  ) : StoreCatalogProvider {
    override val requiredCapabilities =
      setOf(ProtocolCapabilities.SHARED_PREFERENCES_INSPECT)
    var logicalNames: List<String> = emptyList()
    var incarnationToken: String = "initial"
    var failure: RuntimeException? = null
    var scanCount: Int = 0
    var closed: Boolean = false

    override fun scan(processName: String): List<CatalogStoreCandidate> {
      scanCount++
      failure?.let { throw it }
      return logicalNames.map { logicalName ->
        val semantics =
          StoreSemantics(
            backend = backend,
            storageScope = StorageScope.CREDENTIAL_PROTECTED,
            supportedValueTypes = PreferenceValueTypeIds.SHARED_PREFERENCES,
            writeConsistency =
              if (backend == StoreBackend.DATASTORE) {
                WriteConsistency.ATOMIC_TRANSACTIONAL
              } else {
                WriteConsistency.BEST_EFFORT_NON_ATOMIC
              }
          )
        val capabilities =
          setOf(StoreCapability(ProtocolCapabilities.SNAPSHOT_GET))
        CatalogStoreCandidate(
          identity =
            StoreSemanticIdentity(
              backend,
              StorageScope.CREDENTIAL_PROTECTED,
              processName,
              logicalName
            ),
          name = logicalName,
          fileName = "$logicalName.xml",
          kind = StoreKind.PREFERENCES,
          semantics = semantics,
          capabilities = capabilities,
          incarnationToken = incarnationToken,
          openAdapter = { FakeCatalogAdapter(semantics, capabilities) }
        )
      }
    }

    override fun close() {
      closed = true
    }
  }

  private class FakeCatalogAdapter(
    override val semantics: StoreSemantics,
    override val capabilities: Set<StoreCapability>
  ) : StoreAdapter {
    override val kind = StoreKind.PREFERENCES
    override val schema = null
    var closed = false

    override suspend fun snapshot(): AdapterSnapshot =
      AdapterSnapshot("fingerprint", emptyPreferencesTree())

    override suspend fun write(
      expectedFingerprint: String,
      operation: com.masaibar.datastore.inspector.protocol.WriteOperation
    ): AdapterWriteResult =
      AdapterWriteResult.Applied(
        AdapterSnapshot("updated", emptyPreferencesTree())
      )

    override fun close() {
      closed = true
    }
  }

  private object DataStoreFactory : StoreAdapterFactory {
    override val providerId = "datastore"

    override fun create(candidate: StoreCandidate): AdapterResolution =
      AdapterResolution.Resolved(
        FakeCatalogAdapter(
          semantics =
            StoreSemantics(
              StoreBackend.DATASTORE,
              StorageScope.CREDENTIAL_PROTECTED,
              PreferenceValueTypeIds.DATASTORE,
              WriteConsistency.ATOMIC_TRANSACTIONAL
            ),
          capabilities =
            setOf(
              StoreCapability(ProtocolCapabilities.PREFERENCES_WRITE),
              StoreCapability(ProtocolCapabilities.STORE_RESET)
            )
        )
      )
  }

  private companion object {
    const val PROCESS = "dev.example"

    fun emptyPreferencesTree(): PreferencesTree =
      PreferencesTree(
        InspectorNode(
          path = emptyList(),
          name = "Preferences",
          type = InspectorValueType.ROOT,
          value = null,
          presence = Presence.NOT_APPLICABLE,
          children = emptyList(),
          capabilities = emptySet()
        )
      )
  }
}
