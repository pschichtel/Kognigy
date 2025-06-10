package tel.schich.kognigy

import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.PingTimeoutException
import tel.schich.kognigy.protocol.PongTimeoutException
import tel.schich.kognigy.protocol.SocketIoPacket

private val pingJobName = CoroutineName("kognigy-ping-job")
private val pongTimeoutJobName = CoroutineName("kognigy-pong-timeout-job")
private val endpointReadyTimeoutJobName = CoroutineName("kognigy-endpoint-ready-timeout-job")

enum class ConnectionSuccessReason {
    ENDPOINT_READY_SIGNAL,
    ASSUMED_READY_WITH_TIMEOUT,
    ASSUMED_READY_WITHOUT_TIMEOUT,
}

class KognigyConnection(
    val session: KognigySession,
    val output: ReceiveChannel<CognigyEvent.OutputEvent>,
    private val wsSession: WebSocketSession,
    private val json: Json,
    val endpointReadyTimeoutMillis: Long,
) {
    private val connectionSuccessReasonPromise = CompletableDeferred<ConnectionSuccessReason>()
    private var pingTimer: Job? = null
    private var pongTimeout: Job? = null
    private var endpointReadyTimeout: Job? = null

    val connectionSuccessReason: Deferred<ConnectionSuccessReason>
        get() = connectionSuccessReasonPromise

    internal suspend fun setupPingTimer(intervalMillis: Long, timeoutMillis: Long) {
        // repeated open-events will just reset the timer
        pingTimer?.cancel()
        val timeoutMessage = "engine.io pong didn't arrive for $timeoutMillis ms!"

        suspend fun fail(reason: CancellationException) {
            connectionSuccessReasonPromise.completeExceptionally(reason)
            wsSession.close()
        }

        pingTimer = wsSession.launch(pingJobName) {
            while (true) {
                delay(intervalMillis)
                val result = withTimeoutOrNull(timeoutMillis) {
                    send(EngineIoPacket.Ping, flush = true)
                }
                if (result == null) {
                    fail(PingTimeoutException(timeoutMessage))
                    break
                }
                if (pongTimeout == null) {
                    pongTimeout = wsSession.launch(pongTimeoutJobName) {
                        delay(timeoutMillis)
                        fail(PongTimeoutException(timeoutMessage))
                    }
                }
            }
        }
    }

    private fun completeConnection(reason: ConnectionSuccessReason) {
        connectionSuccessReasonPromise.complete(reason)
    }

    internal fun setupEndpointReadyTimeout() {
        endpointReadyTimeout?.cancel()
        when {
            endpointReadyTimeoutMillis < 0 -> {}
            endpointReadyTimeoutMillis == 0L -> {
                completeConnection(ConnectionSuccessReason.ASSUMED_READY_WITHOUT_TIMEOUT)
            }
            else -> {
                endpointReadyTimeout = wsSession.launch(endpointReadyTimeoutJobName) {
                    delay(endpointReadyTimeoutMillis)
                    completeConnection(ConnectionSuccessReason.ASSUMED_READY_WITH_TIMEOUT)
                }
            }
        }
    }

    internal fun onEndpointReady() {
        endpointReadyTimeout?.cancel()
        completeConnection(ConnectionSuccessReason.ENDPOINT_READY_SIGNAL)
    }

    internal fun onConnectError(cause: Throwable) {
        connectionSuccessReasonPromise.completeExceptionally(cause)
    }

    internal fun onPong() {
        pongTimeout?.cancel()
        pongTimeout = null
    }

    internal suspend fun send(packet: EngineIoPacket, flush: Boolean) {
        wsSession.send(EngineIoPacket.encode(json, packet))
        if (flush) {
            wsSession.flush()
        }
    }

    internal suspend fun send(packet: SocketIoPacket, flush: Boolean) {
        send(SocketIoPacket.encode(json, packet), flush)
    }

    @Suppress("LongParameterList")
    suspend fun sendInput(
        text: String,
        data: JsonElement? = null,
        reloadFlow: Boolean = false,
        resetFlow: Boolean = false,
        resetState: Boolean = false,
        resetContext: Boolean = false,
        flush: Boolean = false,
    ) {
        val event = CognigyEvent.ProcessInput(
            urlToken = session.endpointToken,
            userId = session.userId,
            sessionId = session.id,
            channel = session.channelName,
            source = session.source,
            passthroughIp = session.passthroughIp,
            reloadFlow = reloadFlow,
            resetFlow = resetFlow,
            resetState = resetState,
            resetContext = resetContext,
            data = data,
            text = text,
        )
        send(event, flush)
    }

    suspend fun send(event: CognigyEvent.EncodableEvent, flush: Boolean = false) {
        send(SocketIoPacket.encode(json, CognigyEvent.encode(json, event)), flush)
    }

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "")) {
        wsSession.close(closeReason)
    }

    fun cancel(cause: CancellationException? = null) {
        wsSession.cancel(cause)
        output.cancel(cause)
    }
}