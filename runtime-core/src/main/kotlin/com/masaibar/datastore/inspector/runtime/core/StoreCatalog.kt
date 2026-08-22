package com.masaibar.datastore.inspector.runtime.core

import android.content.Context
import com.masaibar.datastore.inspector.protocol.ProtoSchemaRef
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreDescriptor
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.StoreStatus
import java.util.ServiceLoader
import java.util.UUID

public class RuntimeConnectionContext(
  public val version: ProtocolVersion,
  capabilities: Set<String>,
  public val sessionId: String
) {
  public val capabilities: Set<String> = capabilities.toSet()
}

public data class StoreSemanticIdentity(
  val backend: StoreBackend,
  val storageScope: StorageScope,
  val processName: String,
  val logicalName: String
)

public data class CatalogStoreCandidate(
  val identity: StoreSemanticIdentity,
  val name: String,
  val fileName: String?,
  val kind: StoreKind,
  val semantics: StoreSemantics,
  val capabilities: Set<StoreCapability>,
  val schema: ProtoSchemaRef? = null,
  val incarnationToken: String = "continuous",
  val openAdapter: () -> StoreAdapter
)

public interface StoreCatalogProvider : AutoCloseable {
  public val providerId: String
  public val requiredCapabilities: Set<String>
    get() = emptySet()

  /** Runtime startupでは保持だけを行い、catalog scanやStore openを開始しません。 */
  public fun initialize(context: Context): StoreCatalogProvider = this

  /** authenticated listStores requestのときだけ呼ばれます。 */
  public fun scan(processName: String): List<CatalogStoreCandidate>

  override fun close(): Unit = Unit
}

public class StoreCatalogException(
  public val code: ProtocolErrorCode,
  public val retryable: Boolean = false,
  cause: Throwable? = null
) : IllegalStateException(code.name, cause)

public class DynamicStoreCatalog(
  providers: List<StoreCatalogProvider>,
  private val leases: SnapshotLeaseCache,
  private val storeIdFactory: () -> String = { UUID.randomUUID().toString() }
) : AutoCloseable {
  private val providers = providers.toList()
  private val recordsByIdentity = LinkedHashMap<StoreSemanticIdentity, CatalogRecord>()
  private var nextGeneration = 0L
  private var closed = false

  init {
    val duplicateProviderIds =
      this.providers.groupingBy(StoreCatalogProvider::providerId).eachCount().filterValues { it > 1 }
    if (duplicateProviderIds.isNotEmpty()) {
      throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR)
    }
  }

  @Synchronized
  public fun refresh(
    context: RuntimeConnectionContext,
    processName: String,
    registryEntries: List<RegistryEntry>
  ): List<CatalogRecord> {
    check(!closed) { "catalogはdispose済みです。" }
    val visibleProviders =
      providers.filter { provider ->
        context.capabilities.containsAll(provider.requiredCapabilities)
      }
    val visibleProviderIds = visibleProviders.mapTo(hashSetOf(), StoreCatalogProvider::providerId)
    val preservedRecords =
      recordsByIdentity.filterValues { record ->
        record.providerId !in visibleProviderIds
      }
    val candidates =
      visibleProviders
        .flatMap { provider ->
          try {
            provider.scan(processName).map { candidate ->
              PendingCandidate(provider, candidate)
            }
          } catch (error: StoreCatalogException) {
            error.rethrowInspectionControlFlow()
            throw error
          } catch (error: StoreAdapterException) {
            error.rethrowInspectionControlFlow()
            throw StoreCatalogException(error.code, error.retryable, error)
          } catch (error: Throwable) {
            error.rethrowInspectionControlFlow()
            throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR, cause = error)
          }
        }
    validateCandidates(candidates, processName, registryEntries)
    if (
      candidates.any { pending -> pending.candidate.identity in preservedRecords } ||
      candidates.size + registryEntries.size > MAX_STORES
    ) {
      throw StoreCatalogException(
        if (candidates.size + registryEntries.size > MAX_STORES) {
          ProtocolErrorCode.STORE_CATALOG_LIMIT
        } else {
          ProtocolErrorCode.STORE_ERROR
        }
      )
    }

    val pendingByIdentity = candidates.associateBy { it.candidate.identity }
    val staleRecords =
      recordsByIdentity.filter { (_, record) ->
        record.providerId in visibleProviderIds &&
          record.identity !in pendingByIdentity
      }
    val replacements = mutableListOf<CatalogRecord>()
    val next =
      LinkedHashMap<StoreSemanticIdentity, CatalogRecord>().apply {
        putAll(preservedRecords)
      }
    candidates.forEach { pending ->
      val existing = recordsByIdentity[pending.candidate.identity]
      val record =
        if (
          existing != null &&
          existing.providerId == pending.provider.providerId &&
          existing.incarnationToken == pending.candidate.incarnationToken
        ) {
          existing.updateCandidate(pending.candidate)
          existing
        } else {
          existing?.let(replacements::add)
          CatalogRecord(
            storeId = storeIdFactory(),
            generation = ++nextGeneration,
            providerId = pending.provider.providerId,
            requiredCapabilities = pending.provider.requiredCapabilities.toSet(),
            candidate = pending.candidate
          )
        }
      next[pending.candidate.identity] = record
    }

    (staleRecords.values + replacements).distinct().forEach(::disposeRecord)
    recordsByIdentity.clear()
    recordsByIdentity.putAll(next)
    return recordsByIdentity.values.filter { record ->
      context.capabilities.containsAll(record.requiredCapabilities)
    }
  }

  @Synchronized
  public fun find(storeId: String, context: RuntimeConnectionContext): CatalogRecord? {
    if (closed) return null
    return recordsByIdentity.values.firstOrNull { record ->
      record.storeId == storeId &&
        context.capabilities.containsAll(record.requiredCapabilities)
    }
  }

  @Synchronized
  public fun entries(context: RuntimeConnectionContext): List<CatalogRecord> =
    if (closed) {
      emptyList()
    } else {
      recordsByIdentity.values.filter { record ->
        context.capabilities.containsAll(record.requiredCapabilities)
      }
    }

  override fun close() {
    val records = synchronized(this) {
      if (closed) return
      closed = true
      recordsByIdentity.values.toList().also { recordsByIdentity.clear() }
    }
    records.forEach(::disposeRecord)
    providers.forEach { provider -> ordinaryFailureOrNull(provider::close) }
  }

  private fun validateCandidates(
    candidates: List<PendingCandidate>,
    processName: String,
    registryEntries: List<RegistryEntry>
  ) {
    val identities = HashSet<StoreSemanticIdentity>()
    candidates.forEach { pending ->
      val candidate = pending.candidate
      if (
        candidate.identity.processName != processName ||
        candidate.identity.backend != candidate.semantics.backend ||
        candidate.identity.storageScope != candidate.semantics.storageScope ||
        candidate.identity.logicalName.isEmpty() ||
        candidate.name.isEmpty() ||
        !identities.add(candidate.identity)
      ) {
        throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR)
      }
    }
    val registryIdentities = registryEntries.map { entry -> semanticIdentity(entry, processName) }.toSet()
    if (identities.any { it in registryIdentities }) {
      throw StoreCatalogException(ProtocolErrorCode.STORE_ERROR)
    }
  }

  private fun semanticIdentity(entry: RegistryEntry, processName: String): StoreSemanticIdentity {
    val adapter = (entry.state as? RegistryState.Resolved)?.adapter
    val semantics = adapter?.semantics ?: defaultStoreSemantics(entry.declaration.kindHint)
    return StoreSemanticIdentity(
      backend = semantics.backend,
      storageScope = semantics.storageScope,
      processName = processName,
      logicalName = entry.declaration.name
    )
  }

  private fun disposeRecord(record: CatalogRecord) {
    leases.invalidateStore(record.storeId)
    record.close()
  }

  private data class PendingCandidate(
    val provider: StoreCatalogProvider,
    val candidate: CatalogStoreCandidate
  )

  public companion object {
    public const val MAX_STORES: Int = 1_024

    public fun loadProviders(classLoader: ClassLoader): List<StoreCatalogProvider> =
      ServiceLoader.load(StoreCatalogProvider::class.java, classLoader).toList()
  }
}

public class CatalogRecord internal constructor(
  public val storeId: String,
  public val generation: Long,
  public val providerId: String,
  public val requiredCapabilities: Set<String>,
  candidate: CatalogStoreCandidate
) : AutoCloseable {
  @Volatile
  private var candidate = candidate
  private var adapter: StoreAdapter? = null
  private var closed = false

  public val identity: StoreSemanticIdentity
    get() = candidate.identity
  public val incarnationToken: String
    get() = candidate.incarnationToken

  @Synchronized
  public fun adapter(): StoreAdapter {
    check(!closed) { "catalog Storeはdispose済みです。" }
    adapter?.let { return it }
    val opened = candidate.openAdapter()
    if (
      opened.kind != candidate.kind ||
      opened.semantics != candidate.semantics ||
      opened.capabilities != candidate.capabilities
    ) {
      ordinaryFailureOrNull(opened::close)
      throw StoreAdapterException(ProtocolErrorCode.STORE_ERROR)
    }
    return opened.also { adapter = it }
  }

  @Synchronized
  internal fun updateCandidate(updated: CatalogStoreCandidate) {
    check(updated.identity == candidate.identity)
    candidate = updated
  }

  public fun descriptor(): StoreDescriptor {
    val current = candidate
    return StoreDescriptor(
      id = storeId,
      name = current.name,
      fileName = current.fileName,
      kind = current.kind,
      status = StoreStatus.RESOLVED,
      capabilities = current.capabilities,
      schema = current.schema,
      unsupportedReason = null,
      semantics = current.semantics,
      generation = generation
    )
  }

  override fun close() {
    val opened = synchronized(this) {
      if (closed) return
      closed = true
      adapter.also { adapter = null }
    }
    opened?.let { ordinaryFailureOrNull(it::close) }
  }
}
