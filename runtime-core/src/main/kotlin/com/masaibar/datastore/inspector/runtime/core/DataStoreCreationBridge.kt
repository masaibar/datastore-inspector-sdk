package com.masaibar.datastore.inspector.runtime.core

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path
import java.io.File

/**
 * ASMがAndroidX DataStore 1.2.1の検証済み作成call siteを置換するbridgeです。
 *
 * produceFile / producePathはAndroidXへそのまま渡し、表示名取得のために追加実行しません。
 */
@InternalDataStoreInspectorApi
public object DataStoreCreationBridge {
  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createSingleProcess(
    factory: DataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    scope: CoroutineScope,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    val observedName = ObservedStoreName()
    val store =
      DataStoreFactory.create(
        serializer = inspection.effective,
        corruptionHandler = corruptionHandler,
        migrations = migrations,
        scope = scope,
        produceFile = observeFileProducer(produceFile, observedName)
      )
    return register(
      store,
      inspection.handle,
      serializer,
      inspection.defaultValue,
      null,
      observedName,
      declarationId,
      declarationOwner,
      declarationProperty
    )
  }

  @JvmStatic
  public fun <T> createSingleProcess(
    factory: DataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcess(
      factory,
      serializer,
      corruptionHandler,
      migrations,
      defaultScope(),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createSingleProcess(
    factory: DataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcess(
      factory,
      serializer,
      corruptionHandler,
      emptyList(),
      defaultScope(),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createSingleProcess(
    factory: DataStoreFactory,
    serializer: Serializer<T>,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcess(
      factory,
      serializer,
      null,
      emptyList(),
      defaultScope(),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createSingleProcessDefault(
    factory: DataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>?,
    scope: CoroutineScope?,
    produceFile: () -> File,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcess(
      factory,
      serializer,
      if (mask and 2 != 0) null else corruptionHandler,
      if (mask and 4 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 8 != 0) defaultScope() else requireNotNull(scope),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createInDeviceProtectedStorage(
    factory: DataStoreFactory,
    context: Context,
    fileName: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    scope: CoroutineScope,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.DEVICE_PROTECTED
      )
    val store =
      DataStoreFactory.createInDeviceProtectedStorage(
        context = context,
        fileName = fileName,
        serializer = inspection.effective,
        corruptionHandler = corruptionHandler,
        migrations = migrations,
        scope = scope
      )
    return register(
      store,
      inspection.handle,
      serializer,
      inspection.defaultValue,
      fileName,
      null,
      declarationId,
      declarationOwner,
      declarationProperty
    )
  }

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  public fun <T> createInDeviceProtectedStorage(
    factory: DataStoreFactory,
    context: Context,
    fileName: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createInDeviceProtectedStorage(
      factory,
      context,
      fileName,
      serializer,
      corruptionHandler,
      migrations,
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  public fun <T> createInDeviceProtectedStorage(
    factory: DataStoreFactory,
    context: Context,
    fileName: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createInDeviceProtectedStorage(
      factory,
      context,
      fileName,
      serializer,
      corruptionHandler,
      emptyList(),
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  public fun <T> createInDeviceProtectedStorage(
    factory: DataStoreFactory,
    context: Context,
    fileName: String,
    serializer: Serializer<T>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createInDeviceProtectedStorage(
      factory,
      context,
      fileName,
      serializer,
      null,
      emptyList(),
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createInDeviceProtectedStorageDefault(
    factory: DataStoreFactory,
    context: Context,
    fileName: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createInDeviceProtectedStorage(
      factory,
      context,
      fileName,
      serializer,
      if (mask and 8 != 0) null else corruptionHandler,
      if (mask and 16 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 32 != 0) defaultScope() else requireNotNull(scope),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createMultiProcess(
    factory: MultiProcessDataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    scope: CoroutineScope,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    val observedName = ObservedStoreName()
    val store =
      MultiProcessDataStoreFactory.create(
        serializer = inspection.effective,
        corruptionHandler = corruptionHandler,
        migrations = migrations,
        scope = scope,
        produceFile = observeFileProducer(produceFile, observedName)
      )
    return register(
      store,
      inspection.handle,
      serializer,
      inspection.defaultValue,
      null,
      observedName,
      declarationId,
      declarationOwner,
      declarationProperty
    )
  }

  @JvmStatic
  public fun <T> createMultiProcess(
    factory: MultiProcessDataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcess(
      factory,
      serializer,
      corruptionHandler,
      migrations,
      defaultScope(),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createMultiProcess(
    factory: MultiProcessDataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcess(
      factory,
      serializer,
      corruptionHandler,
      emptyList(),
      defaultScope(),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createMultiProcess(
    factory: MultiProcessDataStoreFactory,
    serializer: Serializer<T>,
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcess(
      factory,
      serializer,
      null,
      emptyList(),
      defaultScope(),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createMultiProcessDefault(
    factory: MultiProcessDataStoreFactory,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>?,
    scope: CoroutineScope?,
    produceFile: () -> File,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcess(
      factory,
      serializer,
      if (mask and 2 != 0) null else corruptionHandler,
      if (mask and 4 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 8 != 0) defaultScope() else requireNotNull(scope),
      produceFile,
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createSingleProcessFromStorage(
    factory: DataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    scope: CoroutineScope,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> {
    val capture = CustomInspectionRegistry.captureForStorage<T>(storage)
    val store = DataStoreFactory.create(storage, corruptionHandler, migrations, scope)
    if (capture != null) {
      return register(
        store,
        capture.handle,
        capture.originalSerializer,
        capture.defaultValue,
        null,
        capture.observedName,
        declarationId,
        declarationOwner,
        declarationProperty
      )
    }
    return store
  }

  @JvmStatic
  public fun <T> createSingleProcessFromStorage(
    factory: DataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcessFromStorage(
      factory,
      storage,
      corruptionHandler,
      migrations,
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createSingleProcessFromStorage(
    factory: DataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcessFromStorage(
      factory,
      storage,
      corruptionHandler,
      emptyList(),
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createSingleProcessFromStorage(
    factory: DataStoreFactory,
    storage: Storage<T>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcessFromStorage(
      factory,
      storage,
      null,
      emptyList(),
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createSingleProcessFromStorageDefault(
    factory: DataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createSingleProcessFromStorage(
      factory,
      storage,
      if (mask and 2 != 0) null else corruptionHandler,
      if (mask and 4 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 8 != 0) defaultScope() else requireNotNull(scope),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createMultiProcessFromStorage(
    factory: MultiProcessDataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    scope: CoroutineScope,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> {
    val capture = CustomInspectionRegistry.captureForStorage<T>(storage)
    val store = MultiProcessDataStoreFactory.create(storage, corruptionHandler, migrations, scope)
    if (capture != null) {
      return register(
        store,
        capture.handle,
        capture.originalSerializer,
        capture.defaultValue,
        null,
        capture.observedName,
        declarationId,
        declarationOwner,
        declarationProperty
      )
    }
    return store
  }

  @JvmStatic
  public fun <T> createMultiProcessFromStorage(
    factory: MultiProcessDataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcessFromStorage(
      factory,
      storage,
      corruptionHandler,
      migrations,
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createMultiProcessFromStorage(
    factory: MultiProcessDataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcessFromStorage(
      factory,
      storage,
      corruptionHandler,
      emptyList(),
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> createMultiProcessFromStorage(
    factory: MultiProcessDataStoreFactory,
    storage: Storage<T>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcessFromStorage(
      factory,
      storage,
      null,
      emptyList(),
      defaultScope(),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> createMultiProcessFromStorageDefault(
    factory: MultiProcessDataStoreFactory,
    storage: Storage<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    migrations: List<DataMigration<T>>?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> =
    createMultiProcessFromStorage(
      factory,
      storage,
      if (mask and 2 != 0) null else corruptionHandler,
      if (mask and 4 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 8 != 0) defaultScope() else requireNotNull(scope),
      declarationId,
      declarationOwner,
      declarationProperty
    )

  @JvmStatic
  public fun <T> fileStorage(
    serializer: Serializer<T>,
    coordinatorProducer: (File) -> InterProcessCoordinator,
    produceFile: () -> File
  ): FileStorage<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    val observedName = ObservedStoreName()
    val storage =
      FileStorage(
        serializer = inspection.effective,
        coordinatorProducer = coordinatorProducer,
        produceFile = observeFileProducer(produceFile, observedName)
      )
    CustomInspectionRegistry.attachStorage(
      storage,
      inspection.toStorageCapture(observedName)
    )
    return storage
  }

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> fileStorageDefault(
    serializer: Serializer<T>,
    coordinatorProducer: ((File) -> InterProcessCoordinator)?,
    produceFile: () -> File,
    mask: Int,
    marker: Any?
  ): FileStorage<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    val observedName = ObservedStoreName()
    val storage =
      if (mask and 2 != 0) {
        FileStorage(
          serializer = inspection.effective,
          produceFile = observeFileProducer(produceFile, observedName)
        )
      } else {
        FileStorage(
          serializer = inspection.effective,
          coordinatorProducer = { file ->
            requireNotNull(coordinatorProducer)(file)
          },
          produceFile = observeFileProducer(produceFile, observedName)
        )
      }
    CustomInspectionRegistry.attachStorage(
      storage,
      inspection.toStorageCapture(observedName)
    )
    return storage
  }

  @JvmStatic
  public fun <T> okioStorage(
    fileSystem: FileSystem,
    serializer: OkioSerializer<T>,
    coordinatorProducer: (Path, FileSystem) -> InterProcessCoordinator,
    producePath: () -> Path
  ): OkioStorage<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    val observedName = ObservedStoreName()
    val storage =
      OkioStorage(
        fileSystem = fileSystem,
        serializer = inspection.effective,
        coordinatorProducer = { path, system ->
          coordinatorProducer(path, system)
        },
        producePath = observePathProducer(producePath, observedName)
      )
    CustomInspectionRegistry.attachStorage(
      storage,
      inspection.toStorageCapture(observedName)
    )
    return storage
  }

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> okioStorageDefault(
    fileSystem: FileSystem,
    serializer: OkioSerializer<T>,
    coordinatorProducer: ((Path, FileSystem) -> InterProcessCoordinator)?,
    producePath: () -> Path,
    mask: Int,
    marker: Any?
  ): OkioStorage<T> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    val observedName = ObservedStoreName()
    val storage =
      if (mask and 4 != 0) {
        OkioStorage(
          fileSystem = fileSystem,
          serializer = inspection.effective,
          producePath = observePathProducer(producePath, observedName)
        )
      } else {
        OkioStorage(
          fileSystem = fileSystem,
          serializer = inspection.effective,
          coordinatorProducer = { path, system ->
            requireNotNull(coordinatorProducer)(path, system)
          },
          producePath = observePathProducer(producePath, observedName)
        )
      }
    CustomInspectionRegistry.attachStorage(
      storage,
      inspection.toStorageCapture(observedName)
    )
    return storage
  }

  private fun <T> register(
    store: DataStore<T>,
    handle: CustomInspectionHandle<T>?,
    originalSerializer: Any,
    defaultValue: T,
    fileName: String?,
    observedName: ObservedStoreName?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<T> {
    if (handle != null) {
      CustomInspectionRegistry.attachStore(store, handle)
    }
    val effectiveFileName = fileName ?: observedName?.current()
    val declaration =
      StoreDeclaration(
        declarationId = declarationId,
        name = effectiveFileName ?: declarationProperty,
        fileName = effectiveFileName,
        kindHint =
          if (isMessageLiteValue(defaultValue)) {
            StoreKind.PROTO
          } else {
            StoreKind.CUSTOM
          },
        owner = declarationOwner,
        property = declarationProperty,
        serializerClassName = originalSerializer.javaClass.name,
        valueClassName = defaultValue?.javaClass?.name
      )
    DataStoreInspectorRuntime.declareGenerated(declaration)
    val entry = DataStoreInspectorRuntime.registerGenerated(store, declaration)
    observedName?.bind(entry.declaration.declarationId)
    return store
  }

  private fun defaultScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

private fun <T, S : Any> SerializerInspection<T, S>.toStorageCapture(
  observedName: ObservedStoreName
): CustomInspectionRegistry.StorageCapture<T> =
  CustomInspectionRegistry.StorageCapture(
    originalSerializer = original,
    defaultValue = defaultValue,
    handle = handle,
    observedName = observedName
  )

internal fun observeFileProducer(
  producer: () -> File,
  observedName: ObservedStoreName
): () -> File =
  {
    producer().also { file -> observedName.observe(file.name) }
  }

internal fun observePathProducer(
  producer: () -> Path,
  observedName: ObservedStoreName
): () -> Path =
  {
    producer().also { path -> observedName.observe(path.name) }
  }
