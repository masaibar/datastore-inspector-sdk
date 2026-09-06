package com.masaibar.datastore.inspector.runtime.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.runtime.core.DataStoreCreationBridge
import com.masaibar.datastore.inspector.runtime.core.DataStoreInspectorRuntime
import com.masaibar.datastore.inspector.runtime.core.InternalDataStoreInspectorApi
import com.masaibar.datastore.inspector.runtime.core.ObservedStoreName
import com.masaibar.datastore.inspector.runtime.core.StoreDeclaration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path
import java.io.File

/** Registers factory-created instances without evaluating file or path producers early. */
@InternalDataStoreInspectorApi
public object PreferencesDataStoreFactoryBridge {
  @JvmStatic
  @JvmOverloads
  public fun createWithPath(
    factory: PreferenceDataStoreFactory,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
    migrations: List<DataMigration<Preferences>> = emptyList(),
    scope: CoroutineScope = defaultScope(),
    produceFile: () -> Path,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> {
    val observedName = ObservedStoreName()
    val store = factory.createWithPath(
      corruptionHandler = corruptionHandler,
      migrations = migrations,
      scope = scope,
      produceFile = { produceFile().also { observedName.observe(it.name) } }
    )
    return register(store, observedName, declarationId, declarationOwner, declarationProperty)
  }

  @JvmStatic
  @JvmOverloads
  public fun createFromFile(
    factory: PreferenceDataStoreFactory,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
    migrations: List<DataMigration<Preferences>> = emptyList(),
    scope: CoroutineScope = defaultScope(),
    produceFile: () -> File,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> {
    val observedName = ObservedStoreName()
    val store = factory.create(
      corruptionHandler = corruptionHandler,
      migrations = migrations,
      scope = scope,
      produceFile = { produceFile().also { observedName.observe(it.name) } }
    )
    return register(store, observedName, declarationId, declarationOwner, declarationProperty)
  }

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun createFromFileDefault(
    factory: PreferenceDataStoreFactory,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?,
    migrations: List<DataMigration<Preferences>>?,
    scope: CoroutineScope?,
    produceFile: () -> File,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> =
    createFromFile(
      factory,
      if (mask and 1 != 0) null else corruptionHandler,
      if (mask and 2 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 4 != 0) defaultScope() else requireNotNull(scope),
      produceFile, declarationId, declarationOwner, declarationProperty
    )

  @JvmStatic
  @JvmOverloads
  public fun createFromStorage(
    factory: PreferenceDataStoreFactory,
    storage: Storage<Preferences>,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
    migrations: List<DataMigration<Preferences>> = emptyList(),
    scope: CoroutineScope = defaultScope(),
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> {
    val store = factory.create(storage, corruptionHandler, migrations, scope)
    return register(
      store, DataStoreCreationBridge.observedNameForStorage(storage),
      declarationId, declarationOwner, declarationProperty
    )
  }

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun createFromStorageDefault(
    factory: PreferenceDataStoreFactory,
    storage: Storage<Preferences>,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?,
    migrations: List<DataMigration<Preferences>>?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> =
    createFromStorage(
      factory, storage,
      if (mask and 2 != 0) null else corruptionHandler,
      if (mask and 4 != 0) emptyList() else requireNotNull(migrations),
      if (mask and 8 != 0) defaultScope() else requireNotNull(scope),
      declarationId, declarationOwner, declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun createWithPathDefault(
    factory: PreferenceDataStoreFactory,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?,
    migrations: List<DataMigration<Preferences>>?,
    scope: CoroutineScope?,
    produceFile: () -> Path,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> =
    createWithPath(
      factory = factory,
      corruptionHandler = if (mask and 1 != 0) null else corruptionHandler,
      migrations = if (mask and 2 != 0) emptyList() else requireNotNull(migrations),
      scope = if (mask and 4 != 0) defaultScope() else requireNotNull(scope),
      produceFile = produceFile,
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty
    )

  private fun register(
    store: DataStore<Preferences>,
    observedName: ObservedStoreName?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): DataStore<Preferences> {
    val fileName = observedName?.current()
    val declaration = StoreDeclaration(
      declarationId = declarationId,
      name = fileName ?: declarationProperty,
      fileName = fileName,
      kindHint = StoreKind.PREFERENCES,
      owner = declarationOwner,
      property = declarationProperty
    )
    DataStoreInspectorRuntime.declareGenerated(declaration)
    val entry = DataStoreInspectorRuntime.registerGenerated(store, declaration)
    observedName?.bind(entry.declaration.declarationId)
    return store
  }

  private fun defaultScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
