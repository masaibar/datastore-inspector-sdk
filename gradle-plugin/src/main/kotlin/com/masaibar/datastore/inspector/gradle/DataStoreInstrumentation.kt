package com.masaibar.datastore.inspector.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

private const val DATA_STORE_DESCRIPTOR = "Landroidx/datastore/core/DataStore;"
private const val RETURN_DESCRIPTOR = "Lkotlin/properties/ReadOnlyProperty;"
private const val CORRUPTION_DESCRIPTOR =
  "Landroidx/datastore/core/handlers/ReplaceFileCorruptionHandler;"
private const val MIGRATIONS_DESCRIPTOR = "Lkotlin/jvm/functions/Function1;"
private const val MIGRATION_LIST_DESCRIPTOR = "Ljava/util/List;"
private const val SCOPE_DESCRIPTOR = "Lkotlinx/coroutines/CoroutineScope;"
private const val SERIALIZER_DESCRIPTOR = "Landroidx/datastore/core/Serializer;"
private const val STORAGE_DESCRIPTOR = "Landroidx/datastore/core/Storage;"
private const val FUNCTION0_DESCRIPTOR = "Lkotlin/jvm/functions/Function0;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val CONTEXT_DESCRIPTOR = "Landroid/content/Context;"
private const val DEFAULT_SUFFIX_DESCRIPTOR = "ILjava/lang/Object;"
private const val DATA_STORE_FACTORY_OWNER = "androidx/datastore/core/DataStoreFactory"
private const val MULTI_PROCESS_FACTORY_OWNER =
  "androidx/datastore/core/MultiProcessDataStoreFactory"
private const val FILE_STORAGE_OWNER = "androidx/datastore/core/FileStorage"
private const val OKIO_STORAGE_OWNER = "androidx/datastore/core/okio/OkioStorage"
private const val CREATION_BRIDGE_OWNER =
  "com/masaibar/datastore/inspector/runtime/core/DataStoreCreationBridge"
private const val STRUCTURED_CAPTURE_OWNER =
  "com/masaibar/datastore/inspector/runtime/core/StructuredSerializationCapture"
private const val INSTRUMENTATION_MARKER_FIELD =
  "\$datastoreInspectorInstrumentation\$issue27\$v1"
private const val INSTRUMENTATION_MARKER_VALUE = "custom-datastore-v1"
private const val METADATA_DESCRIPTOR =
  "$STRING_DESCRIPTOR$STRING_DESCRIPTOR$STRING_DESCRIPTOR"

private const val PREFERENCES_EXPLICIT_DESCRIPTOR =
  "(Ljava/lang/String;$CORRUPTION_DESCRIPTOR$MIGRATIONS_DESCRIPTOR$SCOPE_DESCRIPTOR)$RETURN_DESCRIPTOR"
private const val PREFERENCES_DEFAULT_DESCRIPTOR =
  "(Ljava/lang/String;$CORRUPTION_DESCRIPTOR$MIGRATIONS_DESCRIPTOR$SCOPE_DESCRIPTOR$DEFAULT_SUFFIX_DESCRIPTOR)$RETURN_DESCRIPTOR"
private const val TYPED_EXPLICIT_DESCRIPTOR =
  "(Ljava/lang/String;$SERIALIZER_DESCRIPTOR$CORRUPTION_DESCRIPTOR$MIGRATIONS_DESCRIPTOR$SCOPE_DESCRIPTOR)$RETURN_DESCRIPTOR"
private const val TYPED_DEFAULT_DESCRIPTOR =
  "(Ljava/lang/String;$SERIALIZER_DESCRIPTOR$CORRUPTION_DESCRIPTOR$MIGRATIONS_DESCRIPTOR$SCOPE_DESCRIPTOR$DEFAULT_SUFFIX_DESCRIPTOR)$RETURN_DESCRIPTOR"

@InternalDataStoreInspectorGradleApi
public abstract class DataStoreDelegateVisitorFactory :
  AsmClassVisitorFactory<InstrumentationParameters.None> {
  override fun isInstrumentable(classData: ClassData): Boolean =
    InstrumentationClassPolicy.isInstrumentable(classData.className)

  override fun createClassVisitor(
    classContext: ClassContext,
    nextClassVisitor: ClassVisitor
  ): ClassVisitor =
    DataStoreCallClassVisitor(
      next = nextClassVisitor,
      onVisited = InstrumentationPerformanceMetricRecorder.callback()
    )
}

internal object InstrumentationClassPolicy {
  private val excludedPrefixes =
    listOf(
      "androidx.datastore.",
      "kotlin.",
      "kotlinx.coroutines.",
      "kotlinx.serialization.",
      "com.masaibar.datastore.inspector.protocol.",
      "com.masaibar.datastore.inspector.runtime."
    )

  fun isInstrumentable(className: String): Boolean =
    excludedPrefixes.none(className::startsWith)
}

internal object InstrumentationBudget {
  const val TARGET_CLASS_COUNT: Int = 25_000
  const val TARGET_ELAPSED_MILLIS: Long = 30_000
  const val REGRESSION_SAMPLE_CLASS_COUNT: Int = 1_000
  const val REGRESSION_SAMPLE_ELAPSED_MILLIS: Long = 5_000
}

internal object InstrumentationPerformanceMetricRecorder {
  const val OUTPUT_DIRECTORY_ENV: String =
    "DATASTORE_INSPECTOR_INSTRUMENTATION_METRICS_DIRECTORY"
  const val CORPUS_CLASS_PREFIX_ENV: String =
    "DATASTORE_INSPECTOR_INSTRUMENTATION_METRICS_CORPUS_PREFIX"

  fun callback(): ((String, Long) -> Unit)? {
    val configured =
      System.getenv(OUTPUT_DIRECTORY_ENV)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val corpusClassPrefix =
      System.getenv(CORPUS_CLASS_PREFIX_ENV)
        ?.takeIf(String::isNotBlank)
    val directory = Path.of(configured).toAbsolutePath().normalize()
    Files.createDirectories(directory)
    return { className, elapsedNanos ->
      require(elapsedNanos >= 0) { "visitor elapsed nanosは非負である必要があります。" }
      val record = Files.createTempFile(directory, "visitor-", ".metric")
      val isCorpusClass =
        corpusClassPrefix != null && className.startsWith(corpusClassPrefix)
      Files.writeString(record, "$elapsedNanos\t$isCorpusClass\n")
    }
  }
}

internal class DataStoreCallClassVisitor(
  next: ClassVisitor,
  private val onVisited: ((String, Long) -> Unit)? = null
) :
  ClassVisitor(Opcodes.ASM9, next) {
  private val startedAtNanos: Long = System.nanoTime()
  private var className: String = "unknown"
  private var alreadyInstrumented: Boolean = false
  private var rewrittenCallCount: Int = 0

  override fun visit(
    version: Int,
    access: Int,
    name: String,
    signature: String?,
    superName: String?,
    interfaces: Array<out String>?
  ) {
    className = name.replace('/', '.')
    super.visit(version, access, name, signature, superName, interfaces)
  }

  override fun visitField(
    access: Int,
    name: String,
    descriptor: String,
    signature: String?,
    value: Any?
  ): FieldVisitor? {
    if (name == INSTRUMENTATION_MARKER_FIELD) {
      check(descriptor == STRING_DESCRIPTOR && value == INSTRUMENTATION_MARKER_VALUE) {
        "DataStore Inspector計装markerが競合しています: $className"
      }
      alreadyInstrumented = true
    }
    return super.visitField(access, name, descriptor, signature, value)
  }

  override fun visitMethod(
    access: Int,
    name: String,
    descriptor: String,
    signature: String?,
    exceptions: Array<out String>?
  ): MethodVisitor {
    val downstream = super.visitMethod(access, name, descriptor, signature, exceptions)
    if (alreadyInstrumented) return downstream
    return InstrumentingMethodVisitor(
      downstream = downstream,
      className = className,
      methodName = name,
      onRewrite = { rewrittenCallCount += 1 }
    )
  }

  override fun visitEnd() {
    if (!alreadyInstrumented && rewrittenCallCount > 0) {
      super.visitField(
        Opcodes.ACC_PRIVATE or
          Opcodes.ACC_STATIC or
          Opcodes.ACC_FINAL or
          Opcodes.ACC_SYNTHETIC,
        INSTRUMENTATION_MARKER_FIELD,
        STRING_DESCRIPTOR,
        null,
        INSTRUMENTATION_MARKER_VALUE
      )?.visitEnd()
    }
    super.visitEnd()
    onVisited?.invoke(className, System.nanoTime() - startedAtNanos)
  }
}

private class InstrumentingMethodVisitor(
  downstream: MethodVisitor,
  private val className: String,
  private val methodName: String,
  private val onRewrite: () -> Unit
) : MethodVisitor(Opcodes.ASM9, downstream) {
  private var callIndex: Int = 0
  private val pendingConstructors = ArrayDeque<PendingConstructor>()

  override fun visitTypeInsn(opcode: Int, type: String) {
    if (opcode == Opcodes.NEW && ConstructorRoute.isKnownOwner(type)) {
      pendingConstructors.addLast(PendingConstructor(type))
      return
    }
    super.visitTypeInsn(opcode, type)
  }

  override fun visitInsn(opcode: Int) {
    val pending = pendingConstructors.lastOrNull()
    if (opcode == Opcodes.DUP && pending != null && !pending.duplicated) {
      pending.duplicated = true
      return
    }
    super.visitInsn(opcode)
  }

  override fun visitMethodInsn(
    opcode: Int,
    owner: String,
    invokedName: String,
    invokedDescriptor: String,
    isInterface: Boolean
  ) {
    val currentIndex = callIndex++
    if (
      opcode == Opcodes.INVOKESPECIAL &&
      invokedName == "<init>" &&
      ConstructorRoute.isKnownOwner(owner)
    ) {
      val pending = pendingConstructors.lastOrNull()
      check(pending != null && pending.owner == owner && pending.duplicated) {
        "既知のDataStore Storage constructorに未対応のbytecode形状があります: " +
          "$className#$methodName $owner$invokedDescriptor"
      }
      val route = ConstructorRoute.find(owner, invokedDescriptor)
        ?: unknownSignature(owner, invokedName, invokedDescriptor)
      pendingConstructors.removeLast()
      super.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        route.bridgeOwner,
        route.bridgeName,
        route.bridgeDescriptor,
        false
      )
      onRewrite()
      return
    }

    val route =
      InvocationRoutes.routes.firstOrNull {
        it.opcode == opcode &&
          it.owner == owner &&
          it.originalName == invokedName &&
          it.originalDescriptor == invokedDescriptor
      }
    if (route == null) {
      if (InvocationRoutes.requiresKnownDescriptor(owner, invokedName)) {
        unknownSignature(owner, invokedName, invokedDescriptor)
      }
      super.visitMethodInsn(opcode, owner, invokedName, invokedDescriptor, isInterface)
      return
    }

    if (route.addDeclarationMetadata) {
      val declarationId =
        "$className#$methodName#$currentIndex#${route.id}"
      super.visitLdcInsn(declarationId)
      super.visitLdcInsn(className)
      super.visitLdcInsn("$methodName@$currentIndex")
    }
    super.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      route.bridgeOwner,
      route.bridgeName,
      route.bridgeDescriptor,
      false
    )
    onRewrite()
  }

  override fun visitEnd() {
    check(pendingConstructors.isEmpty()) {
      "既知のDataStore Storage constructorを安全に計装できませんでした: " +
        "$className#$methodName ${pendingConstructors.map { it.owner }}"
    }
    super.visitEnd()
  }

  private fun unknownSignature(
    owner: String,
    invokedName: String,
    descriptor: String
  ): Nothing =
    throw IllegalStateException(
      "既知のDataStore APIに未知のsignatureがあります: " +
        "$owner.$invokedName$descriptor"
    )
}

private data class PendingConstructor(
  val owner: String,
  var duplicated: Boolean = false
)

internal data class InvocationRoute(
  val id: String,
  val opcode: Int,
  val owner: String,
  val originalName: String,
  val originalDescriptor: String,
  val bridgeOwner: String,
  val bridgeName: String,
  val bridgeDescriptor: String,
  val addDeclarationMetadata: Boolean,
  val unknownDescriptorIsError: Boolean
)

internal object InvocationRoutes {
  val routes: List<InvocationRoute> =
    buildList {
      DelegateGenerationRoute.entries.forEach { route ->
        add(
          InvocationRoute(
            id = route.id,
            opcode = Opcodes.INVOKESTATIC,
            owner = route.owner,
            originalName = route.originalName,
            originalDescriptor = route.originalDescriptor,
            bridgeOwner = route.bridgeOwner,
            bridgeName = route.bridgeName,
            bridgeDescriptor = route.bridgeDescriptor,
            addDeclarationMetadata = true,
            unknownDescriptorIsError = true
          )
        )
      }
      addFactoryRoutes(
        factoryOwner = DATA_STORE_FACTORY_OWNER,
        singleProcess = true,
        includeDeviceProtected = true
      )
      addFactoryRoutes(
        factoryOwner = MULTI_PROCESS_FACTORY_OWNER,
        singleProcess = false,
        includeDeviceProtected = false
      )
      addStructuredSerializationRoutes()
    }

  private val strictOwnerNames: Set<Pair<String, String>> =
    routes
      .filter(InvocationRoute::unknownDescriptorIsError)
      .mapTo(linkedSetOf()) { it.owner to it.originalName }

  fun requiresKnownDescriptor(owner: String, name: String): Boolean =
    owner to name in strictOwnerNames

  private fun MutableList<InvocationRoute>.addFactoryRoutes(
    factoryOwner: String,
    singleProcess: Boolean,
    includeDeviceProtected: Boolean
  ) {
    val factoryDescriptor = "L$factoryOwner;"
    val serializerBridge =
      if (singleProcess) "createSingleProcess" else "createMultiProcess"
    val serializerDefaultBridge = "${serializerBridge}Default"
    val storageBridge =
      if (singleProcess) "createSingleProcessFromStorage" else "createMultiProcessFromStorage"
    val storageDefaultBridge = "${storageBridge}Default"
    val processId = if (singleProcess) "single" else "multi"

    val serializerArguments =
      listOf(
        "$SERIALIZER_DESCRIPTOR$CORRUPTION_DESCRIPTOR" +
          "$MIGRATION_LIST_DESCRIPTOR$SCOPE_DESCRIPTOR$FUNCTION0_DESCRIPTOR",
        "$SERIALIZER_DESCRIPTOR$CORRUPTION_DESCRIPTOR" +
          "$MIGRATION_LIST_DESCRIPTOR$FUNCTION0_DESCRIPTOR",
        "$SERIALIZER_DESCRIPTOR$CORRUPTION_DESCRIPTOR$FUNCTION0_DESCRIPTOR",
        "$SERIALIZER_DESCRIPTOR$FUNCTION0_DESCRIPTOR"
      )
    serializerArguments.forEachIndexed { index, arguments ->
      val originalDescriptor = "($arguments)$DATA_STORE_DESCRIPTOR"
      add(
        factoryInvocationRoute(
          id = "$processId-factory-serializer-${index + 1}-v1",
          factoryOwner = factoryOwner,
          originalName = "create",
          originalDescriptor = originalDescriptor,
          bridgeName = serializerBridge,
          bridgeDescriptor =
            "($factoryDescriptor$arguments$METADATA_DESCRIPTOR)$DATA_STORE_DESCRIPTOR"
        )
      )
    }
    val serializerDefaultArguments =
      "$factoryDescriptor$SERIALIZER_DESCRIPTOR$CORRUPTION_DESCRIPTOR" +
        "$MIGRATION_LIST_DESCRIPTOR$SCOPE_DESCRIPTOR$FUNCTION0_DESCRIPTOR" +
        DEFAULT_SUFFIX_DESCRIPTOR
    add(
      staticFactoryInvocationRoute(
        id = "$processId-factory-serializer-default-v1",
        factoryOwner = factoryOwner,
        originalName = "create\$default",
        originalDescriptor = "($serializerDefaultArguments)$DATA_STORE_DESCRIPTOR",
        bridgeName = serializerDefaultBridge
      )
    )

    val storageArguments =
      listOf(
        "$STORAGE_DESCRIPTOR$CORRUPTION_DESCRIPTOR" +
          "$MIGRATION_LIST_DESCRIPTOR$SCOPE_DESCRIPTOR",
        "$STORAGE_DESCRIPTOR$CORRUPTION_DESCRIPTOR$MIGRATION_LIST_DESCRIPTOR",
        "$STORAGE_DESCRIPTOR$CORRUPTION_DESCRIPTOR",
        STORAGE_DESCRIPTOR
      )
    storageArguments.forEachIndexed { index, arguments ->
      val originalDescriptor = "($arguments)$DATA_STORE_DESCRIPTOR"
      add(
        factoryInvocationRoute(
          id = "$processId-factory-storage-${index + 1}-v1",
          factoryOwner = factoryOwner,
          originalName = "create",
          originalDescriptor = originalDescriptor,
          bridgeName = storageBridge,
          bridgeDescriptor =
            "($factoryDescriptor$arguments$METADATA_DESCRIPTOR)$DATA_STORE_DESCRIPTOR"
        )
      )
    }
    val storageDefaultArguments =
      "$factoryDescriptor$STORAGE_DESCRIPTOR$CORRUPTION_DESCRIPTOR" +
        "$MIGRATION_LIST_DESCRIPTOR$SCOPE_DESCRIPTOR$DEFAULT_SUFFIX_DESCRIPTOR"
    add(
      staticFactoryInvocationRoute(
        id = "$processId-factory-storage-default-v1",
        factoryOwner = factoryOwner,
        originalName = "create\$default",
        originalDescriptor = "($storageDefaultArguments)$DATA_STORE_DESCRIPTOR",
        bridgeName = storageDefaultBridge
      )
    )

    if (includeDeviceProtected) {
      val deviceArguments =
        listOf(
          "$CONTEXT_DESCRIPTOR$STRING_DESCRIPTOR$SERIALIZER_DESCRIPTOR" +
            "$CORRUPTION_DESCRIPTOR$MIGRATION_LIST_DESCRIPTOR$SCOPE_DESCRIPTOR",
          "$CONTEXT_DESCRIPTOR$STRING_DESCRIPTOR$SERIALIZER_DESCRIPTOR" +
            "$CORRUPTION_DESCRIPTOR$MIGRATION_LIST_DESCRIPTOR",
          "$CONTEXT_DESCRIPTOR$STRING_DESCRIPTOR$SERIALIZER_DESCRIPTOR" +
            CORRUPTION_DESCRIPTOR,
          "$CONTEXT_DESCRIPTOR$STRING_DESCRIPTOR$SERIALIZER_DESCRIPTOR"
        )
      deviceArguments.forEachIndexed { index, arguments ->
        add(
          factoryInvocationRoute(
            id = "device-protected-factory-${index + 1}-v1",
            factoryOwner = factoryOwner,
            originalName = "createInDeviceProtectedStorage",
            originalDescriptor = "($arguments)$DATA_STORE_DESCRIPTOR",
            bridgeName = "createInDeviceProtectedStorage",
            bridgeDescriptor =
              "($factoryDescriptor$arguments$METADATA_DESCRIPTOR)" +
                DATA_STORE_DESCRIPTOR
          )
        )
      }
      val defaultArguments =
        "$factoryDescriptor$CONTEXT_DESCRIPTOR$STRING_DESCRIPTOR$SERIALIZER_DESCRIPTOR" +
          "$CORRUPTION_DESCRIPTOR$MIGRATION_LIST_DESCRIPTOR$SCOPE_DESCRIPTOR" +
          DEFAULT_SUFFIX_DESCRIPTOR
      add(
        staticFactoryInvocationRoute(
          id = "device-protected-factory-default-v1",
          factoryOwner = factoryOwner,
          originalName = "createInDeviceProtectedStorage\$default",
          originalDescriptor = "($defaultArguments)$DATA_STORE_DESCRIPTOR",
          bridgeName = "createInDeviceProtectedStorageDefault"
        )
      )
    }
  }

  private fun factoryInvocationRoute(
    id: String,
    factoryOwner: String,
    originalName: String,
    originalDescriptor: String,
    bridgeName: String,
    bridgeDescriptor: String
  ): InvocationRoute =
    InvocationRoute(
      id = id,
      opcode = Opcodes.INVOKEVIRTUAL,
      owner = factoryOwner,
      originalName = originalName,
      originalDescriptor = originalDescriptor,
      bridgeOwner = CREATION_BRIDGE_OWNER,
      bridgeName = bridgeName,
      bridgeDescriptor = bridgeDescriptor,
      addDeclarationMetadata = true,
      unknownDescriptorIsError = true
    )

  private fun staticFactoryInvocationRoute(
    id: String,
    factoryOwner: String,
    originalName: String,
    originalDescriptor: String,
    bridgeName: String
  ): InvocationRoute =
    InvocationRoute(
      id = id,
      opcode = Opcodes.INVOKESTATIC,
      owner = factoryOwner,
      originalName = originalName,
      originalDescriptor = originalDescriptor,
      bridgeOwner = CREATION_BRIDGE_OWNER,
      bridgeName = bridgeName,
      bridgeDescriptor = appendMetadata(originalDescriptor),
      addDeclarationMetadata = true,
      unknownDescriptorIsError = true
    )

  private fun MutableList<InvocationRoute>.addStructuredSerializationRoutes() {
    val binaryInterface = "kotlinx/serialization/BinaryFormat"
    val stringInterface = "kotlinx/serialization/StringFormat"
    val serializationStrategy = "Lkotlinx/serialization/SerializationStrategy;"
    val deserializationStrategy = "Lkotlinx/serialization/DeserializationStrategy;"
    val structured =
      listOf(
        StructuredRouteSource(
          owner = binaryInterface,
          opcode = Opcodes.INVOKEINTERFACE,
          bridgeReceiver = binaryInterface,
          name = "encodeToByteArray",
          descriptor = "($serializationStrategy" + "Ljava/lang/Object;)[B"
        ),
        StructuredRouteSource(
          owner = binaryInterface,
          opcode = Opcodes.INVOKEINTERFACE,
          bridgeReceiver = binaryInterface,
          name = "decodeFromByteArray",
          descriptor = "($deserializationStrategy[B)Ljava/lang/Object;"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/cbor/Cbor",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = binaryInterface,
          name = "encodeToByteArray",
          descriptor = "($serializationStrategy" + "Ljava/lang/Object;)[B"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/cbor/Cbor",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = binaryInterface,
          name = "decodeFromByteArray",
          descriptor = "($deserializationStrategy[B)Ljava/lang/Object;"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/cbor/Cbor\$Default",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = binaryInterface,
          name = "encodeToByteArray",
          descriptor = "($serializationStrategy" + "Ljava/lang/Object;)[B"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/cbor/Cbor\$Default",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = binaryInterface,
          name = "decodeFromByteArray",
          descriptor = "($deserializationStrategy[B)Ljava/lang/Object;"
        ),
        StructuredRouteSource(
          owner = stringInterface,
          opcode = Opcodes.INVOKEINTERFACE,
          bridgeReceiver = stringInterface,
          name = "encodeToString",
          descriptor =
            "($serializationStrategy" +
              "Ljava/lang/Object;)Ljava/lang/String;"
        ),
        StructuredRouteSource(
          owner = stringInterface,
          opcode = Opcodes.INVOKEINTERFACE,
          bridgeReceiver = stringInterface,
          name = "decodeFromString",
          descriptor =
            "($deserializationStrategy" +
              "Ljava/lang/String;)Ljava/lang/Object;"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/json/Json",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = stringInterface,
          name = "encodeToString",
          descriptor =
            "($serializationStrategy" +
              "Ljava/lang/Object;)Ljava/lang/String;"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/json/Json",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = stringInterface,
          name = "decodeFromString",
          descriptor =
            "($deserializationStrategy" +
              "Ljava/lang/String;)Ljava/lang/Object;"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/json/Json\$Default",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = stringInterface,
          name = "encodeToString",
          descriptor =
            "($serializationStrategy" +
              "Ljava/lang/Object;)Ljava/lang/String;"
        ),
        StructuredRouteSource(
          owner = "kotlinx/serialization/json/Json\$Default",
          opcode = Opcodes.INVOKEVIRTUAL,
          bridgeReceiver = stringInterface,
          name = "decodeFromString",
          descriptor =
            "($deserializationStrategy" +
              "Ljava/lang/String;)Ljava/lang/Object;"
        )
      )
    structured.forEach { source ->
      add(
        InvocationRoute(
          id = "structured-${source.name}-v1",
          opcode = source.opcode,
          owner = source.owner,
          originalName = source.name,
          originalDescriptor = source.descriptor,
          bridgeOwner = STRUCTURED_CAPTURE_OWNER,
          bridgeName = source.name,
          bridgeDescriptor =
            prependReceiver(source.bridgeReceiver, source.descriptor),
          addDeclarationMetadata = false,
          unknownDescriptorIsError = true
        )
      )
    }
  }

  private data class StructuredRouteSource(
    val owner: String,
    val opcode: Int,
    val bridgeReceiver: String,
    val name: String,
    val descriptor: String
  )
}

internal enum class DelegateGenerationRoute(
  val id: String,
  val owner: String,
  val originalName: String,
  val originalDescriptor: String,
  val bridgeOwner: String,
  val bridgeName: String
) {
  PREFERENCES_DEFAULT(
    id = "preferences-delegate-default-v1",
    owner = "androidx/datastore/preferences/PreferenceDataStoreDelegateKt",
    originalName = "preferencesDataStore\$default",
    originalDescriptor = PREFERENCES_DEFAULT_DESCRIPTOR,
    bridgeOwner = "com/masaibar/datastore/inspector/runtime/preferences/PreferencesDataStoreDelegateBridge",
    bridgeName = "preferencesDataStoreDefault"
  ),
  PREFERENCES_EXPLICIT(
    id = "preferences-delegate-explicit-v1",
    owner = "androidx/datastore/preferences/PreferenceDataStoreDelegateKt",
    originalName = "preferencesDataStore",
    originalDescriptor = PREFERENCES_EXPLICIT_DESCRIPTOR,
    bridgeOwner = "com/masaibar/datastore/inspector/runtime/preferences/PreferencesDataStoreDelegateBridge",
    bridgeName = "preferencesDataStore"
  ),
  TYPED_DEFAULT(
    id = "typed-delegate-default-v1",
    owner = "androidx/datastore/DataStoreDelegateKt",
    originalName = "dataStore\$default",
    originalDescriptor = TYPED_DEFAULT_DESCRIPTOR,
    bridgeOwner = "com/masaibar/datastore/inspector/runtime/core/TypedDataStoreDelegateBridge",
    bridgeName = "dataStoreDefault"
  ),
  TYPED_EXPLICIT(
    id = "typed-delegate-explicit-v1",
    owner = "androidx/datastore/DataStoreDelegateKt",
    originalName = "dataStore",
    originalDescriptor = TYPED_EXPLICIT_DESCRIPTOR,
    bridgeOwner = "com/masaibar/datastore/inspector/runtime/core/TypedDataStoreDelegateBridge",
    bridgeName = "dataStore"
  ),
  DEVICE_PROTECTED_DEFAULT(
    id = "device-protected-delegate-default-v1",
    owner = "androidx/datastore/DataStoreDelegateKt",
    originalName = "deviceProtectedDataStore\$default",
    originalDescriptor = TYPED_DEFAULT_DESCRIPTOR,
    bridgeOwner = "com/masaibar/datastore/inspector/runtime/core/TypedDataStoreDelegateBridge",
    bridgeName = "deviceProtectedDataStoreDefault"
  ),
  DEVICE_PROTECTED_EXPLICIT(
    id = "device-protected-delegate-explicit-v1",
    owner = "androidx/datastore/DataStoreDelegateKt",
    originalName = "deviceProtectedDataStore",
    originalDescriptor = TYPED_EXPLICIT_DESCRIPTOR,
    bridgeOwner = "com/masaibar/datastore/inspector/runtime/core/TypedDataStoreDelegateBridge",
    bridgeName = "deviceProtectedDataStore"
  )
  ;

  val bridgeDescriptor: String = appendMetadata(originalDescriptor)
}

internal data class ConstructorRoute(
  val owner: String,
  val originalDescriptor: String,
  val bridgeOwner: String,
  val bridgeName: String,
  val bridgeDescriptor: String
) {
  companion object {
    internal val routes =
      listOf(
        route(
          owner = FILE_STORAGE_OWNER,
          originalDescriptor =
            "($SERIALIZER_DESCRIPTOR$MIGRATIONS_DESCRIPTOR$FUNCTION0_DESCRIPTOR)V",
          bridgeName = "fileStorage"
        ),
        route(
          owner = FILE_STORAGE_OWNER,
          originalDescriptor =
            "($SERIALIZER_DESCRIPTOR$MIGRATIONS_DESCRIPTOR$FUNCTION0_DESCRIPTOR" +
              "ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
          bridgeName = "fileStorageDefault"
        ),
        route(
          owner = OKIO_STORAGE_OWNER,
          originalDescriptor =
            "(Lokio/FileSystem;Landroidx/datastore/core/okio/OkioSerializer;" +
              "Lkotlin/jvm/functions/Function2;$FUNCTION0_DESCRIPTOR)V",
          bridgeName = "okioStorage"
        ),
        route(
          owner = OKIO_STORAGE_OWNER,
          originalDescriptor =
            "(Lokio/FileSystem;Landroidx/datastore/core/okio/OkioSerializer;" +
              "Lkotlin/jvm/functions/Function2;$FUNCTION0_DESCRIPTOR" +
              "ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
          bridgeName = "okioStorageDefault"
        )
      )

    fun isKnownOwner(owner: String): Boolean =
      owner == FILE_STORAGE_OWNER || owner == OKIO_STORAGE_OWNER

    fun find(owner: String, descriptor: String): ConstructorRoute? =
      routes.firstOrNull { it.owner == owner && it.originalDescriptor == descriptor }

    private fun route(
      owner: String,
      originalDescriptor: String,
      bridgeName: String
    ): ConstructorRoute =
      ConstructorRoute(
        owner = owner,
        originalDescriptor = originalDescriptor,
        bridgeOwner = CREATION_BRIDGE_OWNER,
        bridgeName = bridgeName,
        bridgeDescriptor =
          originalDescriptor
            .removeSuffix("V")
            .replace(
              "Lkotlin/jvm/internal/DefaultConstructorMarker;",
              "Ljava/lang/Object;"
            ) +
            "L$owner;"
      )
  }
}

private fun appendMetadata(descriptor: String): String =
  descriptor.replaceFirst(")", "$METADATA_DESCRIPTOR)")

private fun prependReceiver(owner: String, descriptor: String): String =
  "(L$owner;${descriptor.removePrefix("(")}"
