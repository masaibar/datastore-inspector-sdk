package com.masaibar.datastore.inspector.runtime.core

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Build
import android.os.Process
import com.masaibar.datastore.inspector.protocol.ErrorResponse
import com.masaibar.datastore.inspector.protocol.HandshakeRequest
import com.masaibar.datastore.inspector.protocol.HandshakeResponse
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolException
import com.masaibar.datastore.inspector.protocol.ProtocolFraming
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.ProtocolNegotiation
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.StoreKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

public object DataStoreInspectorRuntime {
  private val lock = Any()
  private val registry = DataStoreRegistry()
  private var customFactory = CustomStoreAdapterFactory()

  @Volatile
  private var factories: List<StoreAdapterFactory> = listOf(customFactory)
  private var server: AuthenticatedLocalServer? = null
  private var storeService: RuntimeStoreService? = null

  public fun registry(): DataStoreRegistry = registry

  /** ASM生成bridgeがdelegate生成時に宣言だけを記録します。 */
  public fun declareGenerated(declaration: StoreDeclaration): RegistryEntry = registry.declare(declaration)

  /** ASM生成bridgeが最初のgetValueで同じDataStore instanceを解決します。 */
  public fun registerGenerated(
    instance: Any,
    declaration: StoreDeclaration
  ): RegistryEntry = registry.resolve(instance, declaration, factories)

  /** 自動計装対象外のKMP Android／Factory経路が、保持済みの同じ実instanceを登録するfallbackです。 */
  public fun registerFallback(
    instance: Any,
    declaration: StoreDeclaration
  ): RegistryEntry = registry.resolve(instance, declaration, factories)

  internal fun updateObservedFileName(
    declarationId: String,
    fileName: String
  ) {
    registry.updateFileName(declarationId, fileName)
  }

  internal fun start(context: Context) {
    if (!supportsInspectorRuntime(Build.VERSION.SDK_INT)) return
    synchronized(lock) {
      if (server != null) return
      val loadedFactories =
        DataStoreRegistry.loadFactories(context.classLoader).map { factory ->
          factory.initialize(context.applicationContext)
        }
      factories = assembleRuntimeFactories(loadedFactories, customFactory)
      InspectorCustomCodecRegistry.load(context.classLoader)
      val loadedCatalogProviders =
        DynamicStoreCatalog.loadProviders(context.classLoader).map { provider ->
          provider.initialize(context.applicationContext)
        }
      val session = RuntimeSession.create()
      val processName = currentProcessName()
      val leases = SnapshotLeaseCache()
      val service =
        RuntimeStoreService(
          registry = registry,
          leases = leases,
          processName = processName,
          catalog = DynamicStoreCatalog(loadedCatalogProviders, leases)
        )
      storeService = service
      server = AuthenticatedLocalServer(session, RuntimeDispatcher(service)).also { it.start() }
      SessionMetadataStore(context).write(session, processName)
    }
  }

  internal fun stop() {
    synchronized(lock) {
      server?.close()
      server = null
      storeService?.close()
      storeService = null
      customFactory.close()
      registry.clear()
      CustomInspectionRegistry.clear()
      InspectorCustomCodecRegistry.clear()
      customFactory = CustomStoreAdapterFactory()
      factories = listOf(customFactory)
    }
  }
}

internal fun assembleRuntimeFactories(
  loadedFactories: List<StoreAdapterFactory>,
  customFactory: StoreAdapterFactory
): List<StoreAdapterFactory> {
  val reservedCollision =
    loadedFactories.any { factory ->
      factory.providerId == CUSTOM_STORE_ADAPTER_PROVIDER_ID
    }
  return buildList {
    if (reservedCollision) add(ReservedCustomFactoryCollision)
    add(customFactory)
    addAll(
      loadedFactories.filterNot { factory ->
        factory.providerId == CUSTOM_STORE_ADAPTER_PROVIDER_ID
      }
    )
  }
}

private object ReservedCustomFactoryCollision : StoreAdapterFactory {
  override val providerId: String = "reserved-custom-provider-collision"

  override fun create(candidate: StoreCandidate): AdapterResolution =
    if (candidate.declaration.kindHint == StoreKind.CUSTOM) {
      AdapterResolution.Error("予約済みCustom Adapter provider IDが競合しています。")
    } else {
      AdapterResolution.NotApplicable
    }
}

internal data class RuntimeSession(
  val sessionId: String,
  val socketName: String,
  val token: String
) {
  companion object {
    fun create(): RuntimeSession =
      RuntimeSession(
        sessionId = UUID.randomUUID().toString(),
        socketName = "datastore_inspector_${randomBytes(16).toHex()}",
        token = randomBytes(32).toHex()
      )

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)
  }
}

internal class SessionMetadataStore(
  private val context: Context
) {
  fun write(
    session: RuntimeSession,
    processName: String
  ) {
    val json = encodeSessionMetadata(session, Process.myPid(), processName)
    val directory = File(context.filesDir, "datastore-inspector").apply { mkdirs() }
    val target = File(directory, "session.json")
    val temporary = File(directory, "session.json.tmp")
    temporary.writeText(json, Charsets.UTF_8)
    check(temporary.renameTo(target)) { "session metadataを確定できません。" }
  }
}

internal fun encodeSessionMetadata(
  session: RuntimeSession,
  pid: Int,
  process: String
): String =
  buildJsonObject {
    put("version", 1)
    put("sessionId", session.sessionId)
    put("socket", session.socketName)
    put("token", session.token)
    put("pid", pid)
    put("process", process)
  }.toString()

private fun currentProcessName(): String = Api28ProcessName.get()

private object Api28ProcessName {
  @android.annotation.TargetApi(28)
  fun get(): String = Application.getProcessName()
}

internal class AuthenticatedLocalServer(
  private val session: RuntimeSession,
  private val dispatcher: RuntimeDispatcher
) : Closeable {
  private val running = AtomicBoolean(false)
  private val activeClient = AtomicReference<LocalSocket?>(null)
  private val subscriptionGeneration = AtomicLong(0)
  private lateinit var socket: LocalServerSocket
  private lateinit var thread: Thread

  fun start() {
    if (!running.compareAndSet(false, true)) return
    socket = LocalServerSocket(session.socketName)
    thread =
      Thread(::acceptLoop, "DataStoreInspectorServer").apply {
        isDaemon = true
        start()
      }
  }

  private fun acceptLoop() {
    while (running.get()) {
      val client =
        ordinaryFailureOrNull {
          socket.accept()
        } ?: break
      if (!activeClient.compareAndSet(null, client)) {
        ordinaryFailureOrNull { client.close() }
        continue
      }
      Thread({
        try {
          client.use(::serve)
        } finally {
          activeClient.compareAndSet(client, null)
        }
      }, "DataStoreInspectorClient").apply {
        isDaemon = true
        start()
      }
    }
  }

  private fun serve(client: LocalSocket) {
    client.soTimeout = 5_000
    val input = DataInputStream(client.inputStream)
    val output = client.outputStream
    val outputLock = Any()
    val handshake =
      ordinaryFailureOrNull {
        readRequest(input, ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)
      } ?: return
    val hello = handshake.payload as? HandshakeRequest ?: return
    if (!constantTimeEquals(hello.sessionId, session.sessionId) ||
      !constantTimeEquals(hello.sessionToken, session.token)
    ) {
      writeResponse(
        output,
        ResponseEnvelope(
          handshake.requestId,
          ErrorResponse(
            ProtocolErrorCode.AUTH_FAILED,
            "認証に失敗しました。",
            false
          )
        ),
        ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES,
        outputLock
      )
      return
    }
    val negotiated =
      ordinaryFailureOrNull {
        ProtocolNegotiation.negotiate(
          ProtocolVersion.CURRENT,
          ProtocolCapabilities.INITIAL,
          hello.version,
          hello.capabilities
        )
      } ?: run {
        writeResponse(
          output,
          ResponseEnvelope(
            handshake.requestId,
            ErrorResponse(
              ProtocolErrorCode.VERSION_MISMATCH,
              "Protocol互換性がありません。",
              false
            )
          ),
          ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES,
          outputLock
        )
        return
      }
    writeResponse(
      output,
      ResponseEnvelope(
        handshake.requestId,
        HandshakeResponse(
          negotiated.version,
          negotiated.capabilities,
          session.sessionId
        )
      ),
      ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES,
      outputLock
    )
    val connectionContext =
      RuntimeConnectionContext(
        version = negotiated.version,
        capabilities = negotiated.capabilities.toSet(),
        sessionId = session.sessionId
      )
    var notificationPublisher: RuntimeNotificationPublisher? = null
    var changeSubscription: AutoCloseable? = null
    try {
      if (ProtocolCapabilities.STORE_CHANGES in connectionContext.capabilities) {
        val generation = subscriptionGeneration.incrementAndGet()
        notificationPublisher =
          RuntimeNotificationPublisher(
            output = output,
            outputLock = outputLock,
            subscriptionGeneration = generation,
            onWriteFailure = {
              ordinaryFailureOrNull { client.close() }
            }
          )
        changeSubscription =
          dispatcher.observeChanges(
            context = connectionContext,
            sink = notificationPublisher,
            subscriptionGeneration = generation
          )
      }
      while (running.get()) {
        val request =
          try {
            readAuthenticatedRequest(
              input = input,
              maximum = ProtocolLimits.AUTHENTICATED_FRAME_BYTES,
              setReadTimeout = { client.soTimeout = it }
            )
          } catch (error: EOFException) {
            error.rethrowInspectionControlFlow()
            return
          } catch (error: Throwable) {
            error.rethrowInspectionControlFlow()
            return
          }
        val response =
          dispatchAuthenticatedRequest(
            dispatcher = dispatcher,
            request = request,
            context = connectionContext
          )
        if (
          !writeResponse(
            output,
            response,
            ProtocolLimits.AUTHENTICATED_FRAME_BYTES,
            outputLock
          )
        ) {
          return
        }
      }
    } finally {
      ordinaryFailureOrNull {
        changeSubscription?.close()
        Unit
      }
      ordinaryFailureOrNull {
        client.close()
        Unit
      }
      ordinaryFailureOrNull {
        notificationPublisher?.close()
        Unit
      }
    }
  }

  private fun writeResponse(
    output: java.io.OutputStream,
    response: ResponseEnvelope,
    maximum: Int,
    outputLock: Any
  ): Boolean {
    return ordinaryFailureOrNull {
      val encoded = ProtocolJson.encodeResponse(response)
      val bounded =
        if (encoded.size <= maximum) {
          encoded
        } else {
          ProtocolJson.encodeResponse(
            ResponseEnvelope(
              response.requestId,
              ErrorResponse(
                ProtocolErrorCode.PAYLOAD_TOO_LARGE,
                "Protocol responseが上限を超えています。",
                false
              )
            )
          )
        }
      val framed = ProtocolFraming.encode(bounded, maximum)
      try {
        synchronized(outputLock) {
          output.write(framed)
          output.flush()
        }
      } finally {
        framed.fill(0)
        if (bounded !== encoded) bounded.fill(0)
        encoded.fill(0)
      }
      true
    } ?: false
  }

  private fun constantTimeEquals(
    actual: String,
    expected: String
  ): Boolean = MessageDigest.isEqual(actual.encodeToByteArray(), expected.encodeToByteArray())

  override fun close() {
    if (!running.compareAndSet(true, false)) return
    ordinaryFailureOrNull {
      activeClient.getAndSet(null)?.close()
      Unit
    }
    ordinaryFailureOrNull { socket.close() }
    if (::thread.isInitialized) thread.interrupt()
  }
}

internal fun dispatchAuthenticatedRequest(
  dispatcher: RuntimeDispatcher,
  request: RequestEnvelope,
  context: RuntimeConnectionContext,
  timeoutMillis: Long = 30_000
): ResponseEnvelope {
  val response =
    try {
      runBlocking {
        if (dispatcher.isBestEffortMutation(request, context)) {
          dispatcher.dispatch(request, context)
        } else {
          withTimeoutOrNull(timeoutMillis) {
            dispatcher.dispatch(request, context)
          }
        }
      }
    } catch (error: Throwable) {
      error.rethrowInspectionControlFlow()
      return ResponseEnvelope(
        request.requestId,
        ErrorResponse(
          ProtocolErrorCode.INTERNAL_ERROR,
          "内部処理に失敗しました。",
          false
        )
      )
    }
  return response ?: ResponseEnvelope(
    request.requestId,
    ErrorResponse(
      ProtocolErrorCode.BUSY,
      "要求処理がtimeoutしました。",
      true
    )
  )
}

internal fun readAuthenticatedRequest(
  input: DataInputStream,
  maximum: Int,
  setReadTimeout: (Int) -> Unit
): RequestEnvelope {
  setReadTimeout(0)
  val firstByte = input.read()
  if (firstByte < 0) throw EOFException()
  setReadTimeout(AUTHENTICATED_FRAME_TIMEOUT_MILLIS)
  return readRequest(input, maximum, firstByte)
}

private fun readRequest(
  input: DataInputStream,
  maximum: Int,
  firstByte: Int? = null
): RequestEnvelope {
  val signedLength =
    if (firstByte == null) {
      input.readInt()
    } else {
      (firstByte shl 24) or
        (input.readUnsignedByte() shl 16) or
        (input.readUnsignedByte() shl 8) or
        input.readUnsignedByte()
    }
  val length = Integer.toUnsignedLong(signedLength)
  if (length > maximum.toLong()) {
    throw ProtocolException(
      com.masaibar.datastore.inspector.protocol.ProtocolFailureKind.PAYLOAD_TOO_LARGE,
      "payloadが上限を超えています。"
    )
  }
  val payload = ByteArray(length.toInt())
  input.readFully(payload)
  return ProtocolJson.decodeRequest(payload)
}

private const val AUTHENTICATED_FRAME_TIMEOUT_MILLIS: Int = 30_000

public class DataStoreInspectorInitProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    val appContext = context?.applicationContext ?: return false
    if (!supportsInspectorRuntime(Build.VERSION.SDK_INT)) return false
    if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
    val currentProcess = currentProcessName()
    if (currentProcess != appContext.applicationInfo.processName) return false
    DataStoreInspectorRuntime.start(appContext)
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(
    uri: Uri,
    values: ContentValues?
  ): Uri? = null

  override fun delete(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = 0
}

internal const val MINIMUM_SUPPORTED_API_LEVEL: Int = 28

internal fun supportsInspectorRuntime(apiLevel: Int): Boolean = apiLevel >= MINIMUM_SUPPORTED_API_LEVEL
