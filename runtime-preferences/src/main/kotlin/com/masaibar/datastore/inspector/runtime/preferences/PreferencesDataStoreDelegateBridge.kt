package com.masaibar.datastore.inspector.runtime.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.runtime.core.DataStoreInspectorRuntime
import com.masaibar.datastore.inspector.runtime.core.InternalDataStoreInspectorApi
import com.masaibar.datastore.inspector.runtime.core.StoreDeclaration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import androidx.datastore.preferences.preferencesDataStore as createPreferencesDataStoreDelegate

/** ASMが`preferencesDataStore` delegate呼び出しを置き換えるdebug Runtime bridgeです。 */
@InternalDataStoreInspectorApi
public object PreferencesDataStoreDelegateBridge {
  @JvmStatic
  public fun preferencesDataStore(
    name: String,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?,
    produceMigrations: (Context) -> List<DataMigration<Preferences>>,
    scope: CoroutineScope,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<Preferences>> =
    wrap(
      delegate = createPreferencesDataStoreDelegate(
        name = name,
        corruptionHandler = corruptionHandler,
        produceMigrations = produceMigrations,
        scope = scope
      ),
      name = name,
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty
    )

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun preferencesDataStoreDefault(
    name: String,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>?,
    produceMigrations: ((Context) -> List<DataMigration<Preferences>>)?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<Preferences>> =
    preferencesDataStore(
      name = name,
      corruptionHandler = if (mask and 2 != 0) null else corruptionHandler,
      produceMigrations = if (mask and 4 != 0) {
        { emptyList() }
      } else {
        requireNotNull(produceMigrations)
      },
      scope = if (mask and 8 != 0) defaultScope() else requireNotNull(scope),
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty
    )

  private fun wrap(
    delegate: ReadOnlyProperty<Context, DataStore<Preferences>>,
    name: String,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<Preferences>> {
    val declaration = StoreDeclaration(
      declarationId = declarationId,
      name = name,
      fileName = name,
      kindHint = StoreKind.PREFERENCES,
      owner = declarationOwner,
      property = declarationProperty
    )
    DataStoreInspectorRuntime.declareGenerated(declaration)
    return object : ReadOnlyProperty<Context, DataStore<Preferences>> {
      override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> {
        val instance = delegate.getValue(thisRef, property)
        DataStoreInspectorRuntime.registerGenerated(instance, declaration)
        return instance
      }
    }
  }

  private fun defaultScope(): CoroutineScope =
    CoroutineScope(Dispatchers.IO + SupervisorJob())
}
