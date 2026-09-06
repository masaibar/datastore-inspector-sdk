package com.masaibar.datastore.inspector.runtime.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.MutatePreferences
import com.masaibar.datastore.inspector.protocol.PutPreference
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.runtime.core.AdapterResolution
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import com.masaibar.datastore.inspector.runtime.core.DataStoreCreationBridge
import com.masaibar.datastore.inspector.runtime.core.DataStoreInspectorRuntime
import com.masaibar.datastore.inspector.runtime.core.StoreCandidate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class PreferencesDataStoreFactoryBridgeSpec : DescribeSpec({
  describe("Preferences factory registration") {
    FactoryRoute.entries.forEach { route ->
      context("when $route opens an existing file with explicit migrations and scope") {
        val directory = Files.createTempDirectory("preferences-path-bridge")
        val file = directory.resolve("settings.preferences_pb").toString().toPath()
        val key = intPreferencesKey("count")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val calls = AtomicInteger()
        val declarationId = "path-${System.nanoTime()}"
        val migration = object : DataMigration<Preferences> {
          override suspend fun shouldMigrate(currentData: Preferences): Boolean = true
          override suspend fun migrate(currentData: Preferences): Preferences =
            currentData.toMutablePreferences().apply { this[key] = (currentData[key] ?: 0) + 1 }
          override suspend fun cleanUp() = Unit
        }

        afterEach {
          scope.coroutineContext[kotlinx.coroutines.Job]?.let { job ->
            job.cancel()
            job.join()
          }
          directory.toFile().deleteRecursively()
        }

        it("preserves persisted values, observes the basename lazily, and edits the registered instance") {
          val seedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
          val seed = PreferenceDataStoreFactory.createWithPath(scope = seedScope) { file }
          try {
            seed.updateData { preferencesOf(key to 41) }
          } finally {
            seedScope.cancel()
            seedScope.coroutineContext[kotlinx.coroutines.Job]?.join()
          }
          val store = route.create(
            migrations = listOf(migration),
            scope = scope,
            produceFile = {
              calls.incrementAndGet()
              file
            },
            declarationId = declarationId
          )
          calls.get() shouldBe 0
          store.data.first()[key] shouldBe 42
          calls.get() shouldBe 1
          val registry = DataStoreInspectorRuntime.registry()
          val entry = registry.entries().single { it.declaration.declarationId == declarationId }
          entry.declaration.kindHint shouldBe StoreKind.PREFERENCES
          entry.declaration.fileName shouldBe "settings.preferences_pb"
          DataStoreInspectorRuntime.registerGenerated(store, entry.declaration).storeId shouldBe entry.storeId
          val adapter = PreferencesStoreAdapterFactory().create(StoreCandidate(store, entry.declaration))
            .shouldBeInstanceOf<AdapterResolution.Resolved>().adapter
          val snapshot = adapter.snapshot()
          adapter.write(snapshot.fingerprint, MutatePreferences(PutPreference("count", IntValue(43))))
            .shouldBeInstanceOf<AdapterWriteResult.Applied>()
          store.data.first()[key] shouldBe 43
          calls.get() shouldBe 1
        }
      }
    }

    FactoryRoute.entries.forEach { route ->
      context("when $route omits handler and migrations but supplies a scope") {
        val directory = Files.createTempDirectory("preferences-path-default")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val declarationId = "path-default-${System.nanoTime()}"

        afterEach {
          scope.cancel()
          scope.coroutineContext[kotlinx.coroutines.Job]?.join()
          directory.toFile().deleteRecursively()
        }

        it("restores default argument mask values and produces a usable store") {
          val store = route.createDefault(
            scope,
            { directory.resolve("default.preferences_pb").toString().toPath() },
            declarationId
          )
          store.data.first().asMap() shouldBe emptyMap()
          DataStoreInspectorRuntime.registry().entries()
            .single { it.declaration.declarationId == declarationId }
            .declaration.fileName shouldBe "default.preferences_pb"
        }
      }
    }
  }

  describe("AndroidX Preferences factory bridge ABI") {
    context("when AndroidX exposes explicit, Java overload, and Kotlin default methods") {
      val originals = PreferenceDataStoreFactory::class.java.declaredMethods
        .filter { it.name in setOf("create", "create\$default", "createWithPath", "createWithPath\$default") }
      val bridges = PreferencesDataStoreFactoryBridge::class.java.declaredMethods

      it("provides a static bridge with the receiver and three metadata strings for every overload") {
        originals.size shouldBe 15
        originals.forEach { original ->
          val isDefault = original.name.endsWith("\$default")
          val parameters =
            (if (isDefault) emptyList() else listOf(PreferenceDataStoreFactory::class.java)) +
              original.parameterTypes.toList() + List(3) { String::class.java }
          val baseName = when {
            original.name.startsWith("createWithPath") -> "createWithPath"
            original.parameterTypes.any { it == androidx.datastore.core.Storage::class.java } -> "createFromStorage"
            else -> "createFromFile"
          }
          bridges.any {
            it.name == (if (isDefault) "${baseName}Default" else baseName) &&
              it.parameterTypes.toList() == parameters && it.returnType == original.returnType &&
              java.lang.reflect.Modifier.isStatic(it.modifiers)
          } shouldBe true
        }
      }
    }
  }
})

private enum class FactoryRoute {
  PATH,
  FILE,
  FILE_STORAGE,
  OKIO_STORAGE;

  fun create(
    migrations: List<DataMigration<Preferences>>,
    scope: CoroutineScope,
    produceFile: () -> Path,
    declarationId: String
  ): DataStore<Preferences> = when (this) {
    PATH -> PreferencesDataStoreFactoryBridge.createWithPath(
      PreferenceDataStoreFactory, null, migrations, scope, produceFile,
      declarationId, "sample.SharedStores", "settings"
    )
    FILE -> PreferencesDataStoreFactoryBridge.createFromFile(
      PreferenceDataStoreFactory, null, migrations, scope, { produceFile().toFile() },
      declarationId, "sample.SharedStores", "settings"
    )
    FILE_STORAGE, OKIO_STORAGE -> PreferencesDataStoreFactoryBridge.createFromStorage(
      PreferenceDataStoreFactory, storage(produceFile), null, migrations, scope,
      declarationId, "sample.SharedStores", "settings"
    )
  }

  fun createDefault(
    scope: CoroutineScope,
    produceFile: () -> Path,
    declarationId: String
  ): DataStore<Preferences> = when (this) {
    PATH -> PreferencesDataStoreFactoryBridge.createWithPathDefault(
      PreferenceDataStoreFactory, null, null, scope, produceFile, 3, null,
      declarationId, "sample.SharedStores", "defaults"
    )
    FILE -> PreferencesDataStoreFactoryBridge.createFromFileDefault(
      PreferenceDataStoreFactory, null, null, scope, { produceFile().toFile() }, 3, null,
      declarationId, "sample.SharedStores", "defaults"
    )
    FILE_STORAGE, OKIO_STORAGE -> PreferencesDataStoreFactoryBridge.createFromStorageDefault(
      PreferenceDataStoreFactory, storage(produceFile), null, null, scope, 6, null,
      declarationId, "sample.SharedStores", "defaults"
    )
  }

  private fun storage(produceFile: () -> Path): androidx.datastore.core.Storage<Preferences> = when (this) {
    FILE_STORAGE -> DataStoreCreationBridge.fileStorageDefault(
      PreferencesFileSerializer, null, { produceFile().toFile() }, 2, null
    )
    OKIO_STORAGE -> DataStoreCreationBridge.okioStorageDefault(
      FileSystem.SYSTEM, PreferencesSerializer, null, produceFile, 4, null
    )
    else -> error("Not a storage route")
  }
}
