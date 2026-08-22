package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.GetSnapshotRequest
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.WriteOperation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException

class RuntimeDispatcherControlFlowSpec :
  DescribeSpec({
    describe("Runtime dispatch control-flow boundary") {
      listOf<Pair<String, () -> Throwable>>(
        "LinkageError" to {
          LinkageError("snapshot linkage failure")
        },
        "CancellationException" to {
          CancellationException("snapshot cancellation")
        }
      ).forEach { (failureName, createFailure) ->
        context("StoreAdapterExceptionのcause chainに$failureName があるとき") {
          lateinit var controlFlow: Throwable
          lateinit var registry: DataStoreRegistry
          lateinit var stores: RuntimeStoreService
          lateinit var dispatcher: RuntimeDispatcher
          lateinit var request: RequestEnvelope
          lateinit var context: RuntimeConnectionContext

          beforeEach {
            controlFlow = createFailure()
            val wrapper =
              StoreAdapterException(
                code = ProtocolErrorCode.STORE_ERROR,
                cause = controlFlow
              )
            registry = DataStoreRegistry { "fatal-store" }
            val entry =
              registry.resolve(
                instance = Any(),
                declaration =
                  StoreDeclaration(
                    declarationId = "fatal-declaration",
                    name = "fatal",
                    fileName = "fatal.preferences_pb",
                    kindHint = StoreKind.PREFERENCES,
                    owner = "fixture.FatalStores",
                    property = "fatal"
                  ),
                factories =
                  listOf(
                    object : StoreAdapterFactory {
                      override val providerId: String = "fatal-adapter"

                      override fun create(
                        candidate: StoreCandidate
                      ): AdapterResolution =
                        AdapterResolution.Resolved(
                          FatalSnapshotAdapter(wrapper)
                        )
                    }
                  )
              )
            stores = RuntimeStoreService(registry)
            dispatcher = RuntimeDispatcher(stores)
            request =
              RequestEnvelope(
                requestId = "fatal-request",
                payload = GetSnapshotRequest(entry.storeId)
              )
            context =
              RuntimeConnectionContext(
                version = ProtocolVersion.CURRENT,
                capabilities = ProtocolCapabilities.INITIAL,
                sessionId = "fatal-session"
              )
          }

          afterEach {
            stores.close()
            registry.clear()
          }

          it("protocol errorへ変換せず同じidentityのcontrol-flow例外を再送出する") {
            val caught =
              shouldThrowAny {
                dispatchAuthenticatedRequest(
                  dispatcher = dispatcher,
                  request = request,
                  context = context
                )
              }

            caught shouldBeSameInstanceAs controlFlow
          }
        }
      }

      context("server fallback境界のcause chainにLinkageErrorがあるとき") {
        val fatal = LinkageError("server boundary linkage failure")
        val wrapper = IOException("server boundary wrapper", fatal)

        it("通常失敗へ変換せず最深例外identityを保つ") {
          val caught =
            shouldThrow<LinkageError> {
              ordinaryFailureOrNull<Unit> { throw wrapper }
            }

          caught shouldBeSameInstanceAs fatal
        }
      }

      context("32階層のordinary wrapperの奥にLinkageErrorがあるとき") {
        lateinit var fatal: LinkageError
        lateinit var wrapper: Throwable

        beforeEach {
          fatal = LinkageError("deep linkage failure")
          wrapper =
            (1..32).fold(fatal as Throwable) { cause, depth ->
              IOException("ordinary wrapper $depth", cause)
            }
        }

        it("深度で打ち切らず同じidentityの例外を再送出する") {
          val caught =
            shouldThrowAny {
              wrapper.rethrowInspectionControlFlow()
            }

          caught shouldBeSameInstanceAs fatal
        }
      }

      listOf<Pair<String, (LinkageError) -> Throwable>>(
        "RuntimeStoreException" to { fatal ->
          RuntimeStoreException.Failed().apply { initCause(fatal) }
        },
        "IllegalArgumentException" to { fatal ->
          IllegalArgumentException("invalid request wrapper", fatal)
        }
      ).forEach { (failureName, createFailure) ->
        context("dispatcherが$failureName のcause chainにLinkageErrorを受け取るとき") {
          lateinit var fatal: LinkageError
          lateinit var harness: SnapshotFailureDispatchHarness

          beforeEach {
            fatal = LinkageError("dispatcher typed catch linkage failure")
            harness = SnapshotFailureDispatchHarness(createFailure(fatal))
          }

          afterEach {
            harness.close()
          }

          it("protocol errorへ変換せず同じidentityのfatal errorを再送出する") {
            val caught =
              shouldThrow<LinkageError> {
                dispatchAuthenticatedRequest(
                  dispatcher = harness.dispatcher,
                  request = harness.request,
                  context = harness.context
                )
              }

            caught shouldBeSameInstanceAs fatal
          }
        }
      }

      context("catalog provider scanのStoreCatalogExceptionがLinkageErrorをcauseに持つとき") {
        lateinit var fatal: LinkageError
        lateinit var catalog: DynamicStoreCatalog
        lateinit var context: RuntimeConnectionContext

        beforeEach {
          fatal = LinkageError("catalog linkage failure")
          catalog =
            DynamicStoreCatalog(
              providers =
                listOf(
                  object : StoreCatalogProvider {
                    override val providerId: String = "fatal-catalog"

                    override fun scan(
                      processName: String
                    ): List<CatalogStoreCandidate> =
                      throw StoreCatalogException(
                        code = ProtocolErrorCode.STORE_ERROR,
                        cause = fatal
                      )
                  }
                ),
              leases = SnapshotLeaseCache()
            )
          context =
            RuntimeConnectionContext(
              version = ProtocolVersion.CURRENT,
              capabilities = ProtocolCapabilities.INITIAL,
              sessionId = "fatal-catalog-session"
            )
        }

        afterEach {
          catalog.close()
        }

        it("StoreCatalogExceptionへ変換せず最深例外identityを保つ") {
          val caught =
            shouldThrow<LinkageError> {
              catalog.refresh(context, "dev.example", emptyList())
            }

          caught shouldBeSameInstanceAs fatal
        }
      }

      context("adapter factoryのcause chainにLinkageErrorがあるとき") {
        lateinit var fatal: LinkageError
        lateinit var registry: DataStoreRegistry
        lateinit var declaration: StoreDeclaration
        lateinit var factory: StoreAdapterFactory

        beforeEach {
          fatal = LinkageError("factory linkage failure")
          registry = DataStoreRegistry { "fatal-factory-store" }
          declaration =
            StoreDeclaration(
              declarationId = "fatal-factory-declaration",
              name = "fatal-factory",
              fileName = "fatal-factory.preferences_pb",
              kindHint = StoreKind.PREFERENCES,
              owner = "fixture.FatalFactoryStores",
              property = "fatalFactory"
            )
          factory =
            object : StoreAdapterFactory {
              override val providerId: String = "fatal-factory"

              override fun create(candidate: StoreCandidate): AdapterResolution =
                throw IOException("factory wrapper", fatal)
            }
        }

        afterEach {
          registry.clear()
        }

        it("AdapterResolution.Errorへ変換せず最深例外identityを保つ") {
          val caught =
            shouldThrow<LinkageError> {
              registry.resolve(Any(), declaration, listOf(factory))
            }

          caught shouldBeSameInstanceAs fatal
        }
      }

      context("MessageLite class lookupのcause chainにLinkageErrorがあるとき") {
        lateinit var fatal: LinkageError
        lateinit var value: Any

        beforeEach {
          fatal = LinkageError("protobuf lookup linkage failure")
          val parentLoader =
            checkNotNull(RuntimeDispatcherControlFlowSpec::class.java.classLoader)
          val loader =
            FatalMessageLiteClassLoader(
              parentLoader,
              fatal
            )
          value =
            Proxy.newProxyInstance(
              loader,
              arrayOf(Runnable::class.java)
            ) { _, _, _ -> Unit }
        }

        it("Custom serializer fallbackへ変換せず最深例外identityを保つ") {
          val caught =
            shouldThrow<LinkageError> {
              isMessageLiteValue(value)
            }

          caught shouldBeSameInstanceAs fatal
        }
      }
    }
  })

private class FatalMessageLiteClassLoader(
  parent: ClassLoader,
  private val fatal: LinkageError
) : ClassLoader(parent) {
  override fun loadClass(
    name: String,
    resolve: Boolean
  ): Class<*> {
    if (name == "com.google.protobuf.MessageLite") throw IOException("lookup wrapper", fatal)
    return super.loadClass(name, resolve)
  }
}

private class FatalSnapshotAdapter(
  private val failure: Throwable
) : StoreAdapter {
  override val kind: StoreKind = StoreKind.PREFERENCES
  override val capabilities: Set<StoreCapability> =
    setOf(StoreCapability(ProtocolCapabilities.SNAPSHOT_GET))
  override val schema = null

  override suspend fun snapshot(): AdapterSnapshot = throw failure

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult = error("writeは呼ばれません。")
}

private class SnapshotFailureDispatchHarness(
  failure: Throwable
) : AutoCloseable {
  private val registry = DataStoreRegistry { "typed-catch-store" }
  private val stores: RuntimeStoreService
  val dispatcher: RuntimeDispatcher
  val request: RequestEnvelope
  val context =
    RuntimeConnectionContext(
      version = ProtocolVersion.CURRENT,
      capabilities = ProtocolCapabilities.INITIAL,
      sessionId = "typed-catch-session"
    )

  init {
    val entry =
      registry.resolve(
        instance = Any(),
        declaration =
          StoreDeclaration(
            declarationId = "typed-catch-declaration",
            name = "typed-catch",
            fileName = "typed-catch.preferences_pb",
            kindHint = StoreKind.PREFERENCES,
            owner = "fixture.TypedCatchStores",
            property = "typedCatch"
          ),
        factories =
          listOf(
            object : StoreAdapterFactory {
              override val providerId: String = "typed-catch-adapter"

              override fun create(
                candidate: StoreCandidate
              ): AdapterResolution =
                AdapterResolution.Resolved(
                  FatalSnapshotAdapter(failure)
                )
            }
          )
      )
    stores = RuntimeStoreService(registry)
    dispatcher = RuntimeDispatcher(stores)
    request =
      RequestEnvelope(
        requestId = "typed-catch-request",
        payload = GetSnapshotRequest(entry.storeId)
      )
  }

  override fun close() {
    stores.close()
    registry.clear()
  }
}
