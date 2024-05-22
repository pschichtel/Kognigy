package tel.schich.kognigy

import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.PingTimeoutException
import tel.schich.kognigy.protocol.SocketIoPacket

class KognigyConnection(
    val session: KognigySession,
    val output: ReceiveChannel<CognigyEvent.OutputEvent>,
    private val wsSession: WebSocketSession,
    private val json: Json,
    private val socketIoConnected: CompletableDeferred<Unit>,
) {
    private var pingTimer: Job? = null
    private var pongTimeout: Job? = null

    internal suspend fun setupPingTimer(intervalMillis: Long, timeoutMillis: Long) {
        pingTimer?.cancel()
        pingTimer = wsSession.launch {
            while (true) {
                delay(intervalMillis)
                send(EngineIoPacket.Ping)
                val timeoutMessage = "engine.io pong didn't arrive for $timeoutMillis ms!"
                if (pongTimeout == null) {
                    pongTimeout = wsSession.launch {
                        delay(timeoutMillis)
                        val reason = PingTimeoutException(timeoutMessage)
                        socketIoConnected.cancel(reason)
                        wsSession.cancel(reason)
                    }
                }
            }
        }
    }

    internal fun onConnected() {
        socketIoConnected.complete(Unit)
    }

    internal fun onPong() {
        pongTimeout?.cancel()
        pongTimeout = null
    }

    internal suspend fun send(packet: EngineIoPacket) {
        wsSession.send(EngineIoPacket.encode(json, packet))
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
        send(SocketIoPacket.encode(json, CognigyEvent.encode(json, event)))
        if (flush) {
            wsSession.flush()
        }
    }

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "")) {
        wsSession.close(closeReason)
    }
}