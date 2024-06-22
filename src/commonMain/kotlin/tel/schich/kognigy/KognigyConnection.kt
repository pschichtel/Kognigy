package tel.schich.kognigy

import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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

class KognigyConnection(
    val session: KognigySession,
    val output: ReceiveChannel<CognigyEvent.OutputEvent>,
    private val receiveScope: CoroutineScope,
    private val wsSession: WebSocketSession,
    private val json: Json,
    private val socketIoConnected: CompletableDeferred<Unit>,
) {
    private var pingTimer: Job? = null
    private var pongTimeout: Job? = null
    private var endpointReadyTimeout: Job? = null

    internal suspend fun setupPingTimer(intervalMillis: Long, timeoutMillis: Long) {
        // repeated open-events will just reset the timer
        pingTimer?.cancel()
        val timeoutMessage = "engine.io pong didn't arrive for $timeoutMillis ms!"

        fun fail(reason: CancellationException) {
            socketIoConnected.completeExceptionally(reason)
            wsSession.cancel(reason)
        }

        pingTimer = wsSession.launch(pingJobName) {
            while (true) {
                delay(intervalMillis)
                try {
                    withTimeout(timeoutMillis) {
                        send(EngineIoPacket.Ping, flush = true)
                    }
                    if (pongTimeout == null) {
                        pongTimeout = wsSession.launch(pongTimeoutJobName) {
                            delay(timeoutMillis)
                            fail(PongTimeoutException(timeoutMessage))
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    fail(PingTimeoutException(timeoutMessage))
                    break
                }
            }
        }
    }

    private fun completeConnection() {
        socketIoConnected.complete(Unit)
    }

    internal fun setupEndpointReadyTimeout(delayMillis: Long, block: suspend () -> Unit) {
        endpointReadyTimeout?.cancel()
        if (delayMillis < 0) {
            return
        }
        if (delayMillis == 0L) {
            completeConnection()
            return
        }
        endpointReadyTimeout = wsSession.launch(endpointReadyTimeoutJobName) {
            delay(delayMillis)
            completeConnection()
            block()
        }
    }

    internal fun onConnectionReady() {
        endpointReadyTimeout?.cancel()
        completeConnection()
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

    suspend fun send(event: CognigyEvent.InputEvent, flush: Boolean = false) {
        send(SocketIoPacket.encode(json, CognigyEvent.encode(json, event)), flush)
    }

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "")) {
        wsSession.close(closeReason)
    }

    fun cancel(cause: CancellationException? = null) {
        receiveScope.cancel(cause)
    }
}