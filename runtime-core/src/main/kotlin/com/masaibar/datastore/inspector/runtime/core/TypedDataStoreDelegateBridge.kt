package com.masaibar.datastore.inspector.runtime.core

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import androidx.datastore.dataStore as createDataStoreDelegate
import androidx.datastore.deviceProtectedDataStore as createDeviceProtectedDataStoreDelegate

/** ASMがtyped `dataStore` delegate呼び出しを置き換えるdebug Runtime bridgeです。 */
@InternalDataStoreInspectorApi
public object TypedDataStoreDelegateBridge {
  @JvmStatic
  public fun <T> dataStore(
    name: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    produceMigrations: (Context) -> List<DataMigration<T>>,
    scope: CoroutineScope,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<T>> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.CREDENTIAL_PROTECTED
      )
    return wrap(
      delegate =
        createDataStoreDelegate(
          fileName = name,
          serializer = inspection.effective,
          corruptionHandler = corruptionHandler,
          produceMigrations = produceMigrations,
          scope = scope
        ),
      name = name,
      serializer = serializer,
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty,
      defaultValue = inspection.defaultValue,
      handle = inspection.handle
    )
  }

  @JvmStatic
  @Suppress("UNUSED_PARAMETER")
  public fun <T> dataStoreDefault(
    name: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    produceMigrations: ((Context) -> List<DataMigration<T>>)?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<T>> =
    dataStore(
      name = name,
      serializer = serializer,
      // DataStore 1.2.1の第3〜5 optional引数に対応: 4=corruptionHandler、8=produceMigrations、16=scope。
      corruptionHandler = if (mask and 4 != 0) null else corruptionHandler,
      produceMigrations =
        if (mask and 8 != 0) {
          { emptyList() }
        } else {
          requireNotNull(produceMigrations)
        },
      scope = if (mask and 16 != 0) defaultScope() else requireNotNull(scope),
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty
    )

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  public fun <T> deviceProtectedDataStore(
    name: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    produceMigrations: (Context) -> List<DataMigration<T>>,
    scope: CoroutineScope,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<T>> {
    val inspection =
      selectSerializerForInspection(
        serializer,
        StorageScope.DEVICE_PROTECTED
      )
    return wrap(
      delegate =
        createDeviceProtectedDataStoreDelegate(
          fileName = name,
          serializer = inspection.effective,
          corruptionHandler = corruptionHandler,
          produceMigrations = produceMigrations,
          scope = scope
        ),
      name = name,
      serializer = serializer,
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty,
      defaultValue = inspection.defaultValue,
      handle = inspection.handle
    )
  }

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.N)
  @Suppress("UNUSED_PARAMETER")
  public fun <T> deviceProtectedDataStoreDefault(
    name: String,
    serializer: Serializer<T>,
    corruptionHandler: ReplaceFileCorruptionHandler<T>?,
    produceMigrations: ((Context) -> List<DataMigration<T>>)?,
    scope: CoroutineScope?,
    mask: Int,
    marker: Any?,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String
  ): ReadOnlyProperty<Context, DataStore<T>> =
    deviceProtectedDataStore(
      name = name,
      serializer = serializer,
      corruptionHandler = if (mask and 4 != 0) null else corruptionHandler,
      produceMigrations =
        if (mask and 8 != 0) {
          { emptyList() }
        } else {
          requireNotNull(produceMigrations)
        },
      scope = if (mask and 16 != 0) defaultScope() else requireNotNull(scope),
      declarationId = declarationId,
      declarationOwner = declarationOwner,
      declarationProperty = declarationProperty
    )

  private fun <T> wrap(
    delegate: ReadOnlyProperty<Context, DataStore<T>>,
    name: String,
    serializer: Serializer<T>,
    declarationId: String,
    declarationOwner: String,
    declarationProperty: String,
    defaultValue: T,
    handle: CustomInspectionHandle<T>?
  ): ReadOnlyProperty<Context, DataStore<T>> {
    val valueClassName = defaultValue?.javaClass?.name
    val declaration =
      StoreDeclaration(
        declarationId = declarationId,
        name = name,
        fileName = name,
        kindHint = if (isMessageLiteValue(defaultValue)) StoreKind.PROTO else StoreKind.CUSTOM,
        owner = declarationOwner,
        property = declarationProperty,
        serializerClassName = serializer.javaClass.name,
        valueClassName = valueClassName
      )
    DataStoreInspectorRuntime.declareGenerated(declaration)
    return object : ReadOnlyProperty<Context, DataStore<T>> {
      override fun getValue(
        thisRef: Context,
        property: KProperty<*>
      ): DataStore<T> {
        val instance = delegate.getValue(thisRef, property)
        if (handle != null) {
          CustomInspectionRegistry.attachStore(instance, handle)
        }
        DataStoreInspectorRuntime.registerGenerated(instance, declaration)
        return instance
      }
    }
  }

  private fun defaultScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
