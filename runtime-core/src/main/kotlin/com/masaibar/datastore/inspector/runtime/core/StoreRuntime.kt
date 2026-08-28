package com.masaibar.datastore.inspector.runtime.core

import android.content.Context
import com.masaibar.datastore.inspector.protocol.GetSchemaResult
import com.masaibar.datastore.inspector.protocol.PreferenceValueTypeIds
import com.masaibar.datastore.inspector.protocol.ProtoSchemaRef
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.SnapshotPayload
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import com.masaibar.datastore.inspector.protocol.WriteOperation
import com.masaibar.datastore.inspector.protocol.WriteOutcomeReason
import kotlinx.coroutines.CoroutineScope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.ServiceLoader
import java.util.UUID

@InternalDataStoreInspectorApi
public data class StoreDeclaration(
  val declarationId: String,
  val name: String,
  val fileName: String?,
  val kindHint: StoreKind,
  val owner: String,
  val property: String,
  val serializerClassName: String? = null,
  val valueClassName: String? = null
)

@InternalDataStoreInspectorApi
public data class StoreCandidate(
  val instance: Any,
  val declaration: StoreDeclaration
)

@InternalDataStoreInspectorApi
public sealed interface AdapterResolution {
  public data class Resolved(
    val adapter: StoreAdapter
  ) : AdapterResolution

  public data class Unsupported(
    val reason: UnsupportedReason
  ) : AdapterResolution

  public data class Error(
    val safeMessage: String
  ) : AdapterResolution

  public data object NotApplicable : AdapterResolution
}

@InternalDataStoreInspectorApi
public data class AdapterSnapshot(
  val fingerprint: String,
  val payload: SnapshotPayload
)

@InternalDataStoreInspectorApi
public sealed interface AdapterObservation {
  public data class Snapshot(
    val snapshot: AdapterSnapshot
  ) : AdapterObservation

  public data class Failure(
    val code: ProtocolErrorCode
  ) : AdapterObservation
}

@InternalDataStoreInspectorApi
public fun interface StoreSnapshotObserver {
  public fun onObservation(observation: AdapterObservation)
}

@InternalDataStoreInspectorApi
public sealed interface AdapterWriteResult {
  public data class Applied(
    val snapshot: AdapterSnapshot
  ) : AdapterWriteResult

  public data class Conflict(
    val snapshot: AdapterSnapshot
  ) : AdapterWriteResult

  public data object AppliedSnapshotUnavailable : AdapterWriteResult

  public data class OutcomeUnknown(
    val reason: WriteOutcomeReason,
    val currentSnapshot: AdapterSnapshot?
  ) : AdapterWriteResult
}

@InternalDataStoreInspectorApi
public class StoreAdapterException(
  public val code: ProtocolErrorCode,
  public val retryable: Boolean = false,
  public val operationStarted: Boolean? = null,
  cause: Throwable? = null
) : IllegalStateException(code.name, cause)

@InternalDataStoreInspectorApi
public class StoreSnapshotUnsupportedException(
  public val reason: UnsupportedReason
) : IllegalStateException(reason.code)

@InternalDataStoreInspectorApi
public interface StoreAdapter : AutoCloseable {
  public val kind: StoreKind
  public val capabilities: Set<StoreCapability>
  public val schema: ProtoSchemaRef?
  public val semantics: StoreSemantics
    get() = defaultStoreSemantics(kind)
  public val requiredCapabilities: Set<String>
    get() = emptySet()
  public val runtimeUnsupportedReason: UnsupportedReason?
    get() = null

  public suspend fun snapshot(): AdapterSnapshot

  /** backend固有の整合性契約に従ってfingerprintを確認し、変更を1回だけ実行します。 */
  public suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult

  /**
   * 接続中だけcanonical stateを購読します。返したhandleはlistener／Flow／Jobをすべて所有し、
   * close後にobserverを呼びません。未対応Storeはnullを返します。
   */
  public fun observe(
    scope: CoroutineScope,
    observer: StoreSnapshotObserver
  ): AutoCloseable? = null

  public fun schema(schemaId: String): GetSchemaResult? = null

  override fun close(): Unit = Unit
}

@InternalDataStoreInspectorApi
public fun defaultStoreSemantics(kind: StoreKind): StoreSemantics =
  when (kind) {
    StoreKind.PREFERENCES ->
      StoreSemantics(
        backend = StoreBackend.DATASTORE,
        storageScope = StorageScope.CREDENTIAL_PROTECTED,
        supportedValueTypes = PreferenceValueTypeIds.DATASTORE,
        writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
      )
    StoreKind.PROTO ->
      StoreSemantics(
        backend = StoreBackend.DATASTORE,
        storageScope = StorageScope.CREDENTIAL_PROTECTED,
        supportedValueTypes = emptySet(),
        writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
      )
    StoreKind.CUSTOM, StoreKind.UNKNOWN ->
      StoreSemantics(
        backend = StoreBackend.UNKNOWN,
        storageScope = StorageScope.UNSPECIFIED,
        supportedValueTypes = emptySet(),
        writeConsistency = WriteConsistency.UNKNOWN
      )
  }

@InternalDataStoreInspectorApi
public interface StoreAdapterFactory {
  public val providerId: String

  /** ServiceLoader生成後に、debug applicationのasset等を安全に読み込むための初期化点です。 */
  public fun initialize(context: Context): StoreAdapterFactory = this

  public fun create(candidate: StoreCandidate): AdapterResolution
}

@InternalDataStoreInspectorApi
public sealed interface RegistryState {
  public data object Declared : RegistryState

  public data class Resolved(
    val adapter: StoreAdapter
  ) : RegistryState

  public data class Unsupported(
    val reason: UnsupportedReason
  ) : RegistryState

  public data class Error(
    val safeMessage: String
  ) : RegistryState
}

@InternalDataStoreInspectorApi
public data class RegistryEntry(
  val storeId: String,
  val declaration: StoreDeclaration,
  val state: RegistryState
)

/** process localで、instanceのobject identityを基準に重複排除するRegistryです。 */
@InternalDataStoreInspectorApi
public class DataStoreRegistry(
  private val storeIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
  private val lock = Any()
  private val declarations = LinkedHashMap<String, RegistryEntry>()
  private val instances = IdentityHashMap<Any, String>()

  public fun declare(declaration: StoreDeclaration): RegistryEntry =
    synchronized(lock) {
      declarations.getOrPut(declaration.declarationId) {
        RegistryEntry(storeIdFactory(), declaration, RegistryState.Declared)
      }
    }

  public fun resolve(
    instance: Any,
    declaration: StoreDeclaration,
    factories: List<StoreAdapterFactory>
  ): RegistryEntry = resolveInternal(instance, declaration, factories, remapDeclarationId = true)

  internal fun resolveUniqueDeclaration(
    instance: Any,
    declaration: StoreDeclaration,
    factories: List<StoreAdapterFactory>
  ): RegistryEntry = resolveInternal(instance, declaration, factories, remapDeclarationId = false)

  private fun resolveInternal(
    instance: Any,
    declaration: StoreDeclaration,
    factories: List<StoreAdapterFactory>,
    remapDeclarationId: Boolean
  ): RegistryEntry =
    synchronized(lock) {
      instances[instance]?.let { existingId ->
        val existing = declarations.values.first { it.storeId == existingId }
        declarations[declaration.declarationId]
          ?.takeIf { it.storeId != existingId && it.state is RegistryState.Declared }
          ?.let { declarations.remove(declaration.declarationId) }
        return@synchronized existing
      }
      val existingDeclaration = declarations[declaration.declarationId]
      val declarationIdIsBound =
        existingDeclaration != null &&
          instances.values.any { storeId -> storeId == existingDeclaration.storeId }
      if (declarationIdIsBound && !remapDeclarationId) {
        throw IllegalArgumentException(
          "A different DataStore instance is already registered for declarationId " +
            "'${declaration.declarationId}'."
        )
      }
      val effectiveDeclaration =
        if (declarationIdIsBound) {
          declaration.copy(declarationId = nextAvailableDeclarationId(declaration.declarationId))
        } else {
          declaration
        }
      val declared = declare(effectiveDeclaration)
      val resolution = classify(StoreCandidate(instance, effectiveDeclaration), factories)
      val updated =
        declared.copy(
          state = resolution.toRegistryState()
        )
      declarations[effectiveDeclaration.declarationId] = updated
      instances[instance] = updated.storeId
      updated
    }

  private fun nextAvailableDeclarationId(baseDeclarationId: String): String {
    var ordinal = 2
    var candidate = "$baseDeclarationId#$ordinal"
    while (declarations.containsKey(candidate)) {
      ordinal += 1
      candidate = "$baseDeclarationId#$ordinal"
    }
    return candidate
  }

  internal fun reclassifyUnsupported(factories: List<StoreAdapterFactory>) {
    synchronized(lock) {
      val instancesByStoreId = instances.entries.associate { (instance, storeId) -> storeId to instance }
      val updates = mutableListOf<Pair<String, RegistryEntry>>()
      declarations.forEach { (declarationId, entry) ->
        val unsupported = entry.state as? RegistryState.Unsupported
        val instance = instancesByStoreId[entry.storeId]
        if (unsupported?.reason?.code == "NO_ADAPTER" && instance != null) {
          updates +=
            declarationId to
            entry.copy(
              state = classify(StoreCandidate(instance, entry.declaration), factories).toRegistryState()
            )
        }
      }
      updates.forEach { (declarationId, entry) -> declarations[declarationId] = entry }
    }
  }

  public fun entries(): List<RegistryEntry> = synchronized(lock) { declarations.values.toList() }

  public fun find(storeId: String): RegistryEntry? = synchronized(lock) { declarations.values.firstOrNull { it.storeId == storeId } }

  internal fun updateFileName(
    declarationId: String,
    fileName: String
  ) {
    synchronized(lock) {
      val existing = declarations[declarationId] ?: return
      declarations[declarationId] =
        existing.copy(
          declaration =
            existing.declaration.copy(
              name = fileName,
              fileName = fileName
            )
        )
    }
  }

  public fun clear() {
    val adapters =
      synchronized(lock) {
        val values = declarations.values.mapNotNull { (it.state as? RegistryState.Resolved)?.adapter }
        declarations.clear()
        instances.clear()
        values
      }
    adapters.forEach { adapter -> ordinaryFailureOrNull(adapter::close) }
  }

  private fun classify(
    candidate: StoreCandidate,
    factories: List<StoreAdapterFactory>
  ): AdapterResolution {
    val duplicateIds = factories.groupingBy(StoreAdapterFactory::providerId).eachCount().filterValues { it > 1 }
    if (duplicateIds.isNotEmpty()) {
      return AdapterResolution.Error("Adapter provider IDが重複しています。")
    }
    factories.forEach { factory ->
      val resolution =
        ordinaryFailureOrNull { factory.create(candidate) }
          ?: return AdapterResolution.Error("Adapterの初期化に失敗しました。")
      if (resolution !is AdapterResolution.NotApplicable) return resolution
    }
    return AdapterResolution.NotApplicable
  }

  private fun AdapterResolution.toRegistryState(): RegistryState =
    when (this) {
      is AdapterResolution.Resolved -> RegistryState.Resolved(adapter)
      is AdapterResolution.Unsupported -> RegistryState.Unsupported(reason)
      is AdapterResolution.Error -> RegistryState.Error(safeMessage)
      AdapterResolution.NotApplicable ->
        RegistryState.Unsupported(
          UnsupportedReason("NO_ADAPTER", "対応するAdapterがありません。", false)
        )
    }

  public companion object {
    public fun loadFactories(classLoader: ClassLoader): List<StoreAdapterFactory> =
      ServiceLoader.load(StoreAdapterFactory::class.java, classLoader).toList()
  }
}

@InternalDataStoreInspectorApi
public class SnapshotLeaseCache(
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val tokenFactory: () -> String = {
    ByteArray(32).also(SecureRandom()::nextBytes).toHex()
  },
  private val ttlMillis: Long = 60_000,
  private val maxEntries: Int = 256,
  private val maxFingerprintBytes: Int = 1024 * 1024
) {
  public data class Lease(
    val revision: Long,
    val token: String
  )

  private data class Record(
    val storeId: String,
    val storeGeneration: Long,
    val revision: Long,
    val fingerprint: String,
    val createdAt: Long,
    val bytes: Int
  )

  private val records = LinkedHashMap<String, Record>(16, 0.75f, true)
  private var nextRevision = 0L
  private var totalBytes = 0

  @Synchronized
  public fun issue(
    storeId: String,
    fingerprint: String,
    storeGeneration: Long = 0
  ): Lease {
    prune()
    val reusable =
      records.entries.firstOrNull { (_, record) ->
        record.storeId == storeId &&
          record.storeGeneration == storeGeneration &&
          record.fingerprint == fingerprint
      }
    if (reusable != null) {
      return Lease(reusable.value.revision, reusable.key)
    }
    val token = tokenFactory()
    val revision = ++nextRevision
    val record =
      Record(
        storeId,
        storeGeneration,
        revision,
        fingerprint,
        nowMillis(),
        fingerprint.encodeToByteArray().size
      )
    records[token] = record
    totalBytes += record.bytes
    prune()
    return Lease(revision, token)
  }

  /** tokenは成功・競合を問わず、検証時に必ず消費します。 */
  @Synchronized
  public fun claim(
    storeId: String,
    revision: Long,
    token: String,
    storeGeneration: Long = 0
  ): String? {
    prune()
    val record = records.remove(token) ?: return null
    totalBytes -= record.bytes
    return record.fingerprint.takeIf {
      record.storeId == storeId &&
        record.storeGeneration == storeGeneration &&
        record.revision == revision
    }
  }

  @Synchronized
  public fun invalidateStore(storeId: String) {
    val iterator = records.entries.iterator()
    while (iterator.hasNext()) {
      val record = iterator.next().value
      if (record.storeId == storeId) {
        totalBytes -= record.bytes
        iterator.remove()
      }
    }
  }

  @Synchronized
  public fun clear() {
    records.clear()
    totalBytes = 0
  }

  @Synchronized
  public fun size(): Int {
    prune()
    return records.size
  }

  private fun prune() {
    val cutoff = nowMillis() - ttlMillis
    val iterator = records.entries.iterator()
    while (iterator.hasNext()) {
      val record = iterator.next().value
      if (record.createdAt <= cutoff || records.size > maxEntries || totalBytes > maxFingerprintBytes) {
        totalBytes -= record.bytes
        iterator.remove()
      } else if (records.size <= maxEntries && totalBytes <= maxFingerprintBytes) {
        break
      }
    }
  }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@InternalDataStoreInspectorApi
public fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
