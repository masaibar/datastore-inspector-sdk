package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ErrorResponse
import com.masaibar.datastore.inspector.protocol.GetSchemaRequest
import com.masaibar.datastore.inspector.protocol.GetSnapshotRequest
import com.masaibar.datastore.inspector.protocol.HandshakeRequest
import com.masaibar.datastore.inspector.protocol.ListStoresRequest
import com.masaibar.datastore.inspector.protocol.ListStoresResult
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.SnapshotResultResponse
import com.masaibar.datastore.inspector.protocol.WriteRequest
import com.masaibar.datastore.inspector.protocol.WriteResultResponse
import kotlinx.coroutines.CancellationException

public class RuntimeDispatcher(private val stores: RuntimeStoreService) {
  internal fun observeChanges(
    context: RuntimeConnectionContext,
    sink: RuntimeStoreChangeSink,
    subscriptionGeneration: Long
  ): AutoCloseable =
    RuntimeStoreChangeCoordinator(
      stores = stores,
      context = context,
      sink = sink,
      subscriptionGeneration = subscriptionGeneration
    )

  public fun isBestEffortMutation(
    request: RequestEnvelope,
    context: RuntimeConnectionContext
  ): Boolean =
    (request.payload as? WriteRequest)?.write?.let { payload ->
      stores.isBestEffortMutation(payload, context)
    } == true

  public suspend fun dispatch(
    request: RequestEnvelope,
    context: RuntimeConnectionContext = TEST_CONTEXT
  ): ResponseEnvelope {
    val response = try {
      when (val payload = request.payload) {
        ListStoresRequest -> ListStoresResult(stores.list(context))
        is GetSnapshotRequest -> SnapshotResultResponse(stores.snapshot(payload.storeId, context))
        is GetSchemaRequest -> stores.schema(payload.schemaId, context)
        is WriteRequest -> WriteResultResponse(stores.write(payload.write, context))
        is HandshakeRequest -> ErrorResponse(
          ProtocolErrorCode.INVALID_REQUEST,
          "handshakeは接続開始時にだけ送信できます。",
          false
        )
      }
    } catch (error: RuntimeStoreException) {
      error.rethrowInspectionControlFlow()
      ErrorResponse(
        code = error.code(),
        safeMessage = error.message ?: "Store処理に失敗しました。",
        retryable = (error as? RuntimeStoreException.Failed)?.retryable ?: false,
        operationStarted = (error as? RuntimeStoreException.Failed)?.operationStarted
      )
    } catch (error: StoreAdapterException) {
      error.rethrowInspectionControlFlow()
      ErrorResponse(
        error.code,
        "Store処理に失敗しました。",
        error.retryable,
        error.operationStarted
      )
    } catch (error: IllegalArgumentException) {
      error.rethrowInspectionControlFlow()
      ErrorResponse(ProtocolErrorCode.INVALID_REQUEST, "要求が不正です。", false)
    } catch (error: CancellationException) {
      error.rethrowInspectionControlFlow()
      throw error
    } catch (error: Throwable) {
      error.rethrowInspectionControlFlow()
      ErrorResponse(ProtocolErrorCode.INTERNAL_ERROR, "内部処理に失敗しました。", false)
    }
    return ResponseEnvelope(request.requestId, response)
  }

  private fun RuntimeStoreException.code(): ProtocolErrorCode = when (this) {
    is RuntimeStoreException.NotFound -> ProtocolErrorCode.STORE_NOT_FOUND
    is RuntimeStoreException.NotReady -> ProtocolErrorCode.STORE_NOT_READY
    is RuntimeStoreException.Unsupported -> ProtocolErrorCode.STORE_UNSUPPORTED
    is RuntimeStoreException.Failed -> protocolCode
    is RuntimeStoreException.Stale -> ProtocolErrorCode.STALE_SNAPSHOT
    is RuntimeStoreException.SchemaNotFound -> ProtocolErrorCode.SCHEMA_NOT_FOUND
    is RuntimeStoreException.Capability -> ProtocolErrorCode.UNSUPPORTED_CAPABILITY
  }

  private companion object {
    val TEST_CONTEXT =
      RuntimeConnectionContext(
        ProtocolVersion.CURRENT,
        ProtocolCapabilities.INITIAL,
        "test"
      )
  }
}
