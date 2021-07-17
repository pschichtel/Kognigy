package tel.schich.kognigy

import io.ktor.client.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.Json
import mu.KLoggable
import tel.schich.kognigy.protocol.*
import tel.schich.kognigy.protocol.CognigyEvent.InputEvent
import tel.schich.kognigy.protocol.CognigyEvent.OutputEvent
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class Session(
    val output: ReceiveChannel<OutputEvent>,
    private val encoder: (InputEvent) -> Frame,
    private val wsSession: DefaultClientWebSocketSession,
) {
    suspend fun send(event: InputEvent) = wsSession.send(encoder(event))
    suspend fun close(closeReason: CloseReason) = wsSession.close(closeReason)
}

class Kognigy(
    private val client: HttpClient,
    private val json: Json,
    private val coroutineScope: CoroutineScope,
) {

    suspend fun connect(uri: URI): Session {
        if (!(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) {
            throw IllegalArgumentException("Protocol must be http or https")
        }

        fun encodeInput(event: InputEvent): Frame =
            encodeEngineIoPacket(json, encodeSocketIoPacket(json, encodeCognigyEvent(json, event)))

        val output = Channel<OutputEvent>(Channel.UNLIMITED)

        val scheme =
            if (uri.scheme.equals("http", false)) "ws"
            else "wss"

        val wsUri = URI(scheme, uri.userInfo, uri.host, uri.port, "/socket.io/", "transport=websocket", null).toString()

        return suspendCoroutine { continuation ->
            coroutineScope.launch {
                try {
                    client.ws(urlString = wsUri) {
                        continuation.resume(Session(output, ::encodeInput, this))
                        handle(this, output)
                    }
                } catch (e: Exception) {
                    logger.error("WebSocket connection failed!", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun handle(session: DefaultClientWebSocketSession, output: SendChannel<OutputEvent>) {
        processWebsocketFrame(session.incoming, output) { encodeEngineIoPacket(json, it) }
    }

    private suspend fun processWebsocketFrame(incoming: ReceiveChannel<Frame>, output: SendChannel<OutputEvent>, sink: suspend (EngineIoPacket) -> Unit) {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> processEngineIoPacket(frame, output, sink)
                is Frame.Binary -> logger.warn { "websocket binary, unable to process binary data: $frame" }
                is Frame.Close -> logger.debug { "websocket close: $frame" }
                is Frame.Ping -> logger.debug { "websocket ping: $frame" }
                is Frame.Pong -> logger.debug { "websocket pong: $frame" }
            }
        }
    }

    private suspend fun processEngineIoPacket(frame: Frame.Text, output: SendChannel<OutputEvent>, sink: suspend (EngineIoPacket) -> Unit) {
        when (val packet = decodeEngineIoPacket(json, frame)) {
            is EngineIoPacket.Open -> logger.debug("engine.io open: $packet")
            is EngineIoPacket.Close -> logger.debug("engine.io close")
            is EngineIoPacket.TextMessage -> processSocketIoPacket(packet, output)
            is EngineIoPacket.Ping -> sink(EngineIoPacket.Pong)
            is EngineIoPacket.Pong -> logger.debug("engine.io pong")
            is EngineIoPacket.Upgrade -> logger.debug("engine.io upgrade")
            is EngineIoPacket.Noop -> logger.debug("engine.io noop")
            is EngineIoPacket.Error -> logger.debug(packet.t) { "received broken engine.io packet: ${packet.reason}, ${packet.data}" }
        }
    }

    private suspend fun processSocketIoPacket(engineIoPacket: EngineIoPacket.TextMessage, output: SendChannel<OutputEvent>) {
        when (val packet = decodeSocketIoPacket(json, engineIoPacket)) {
            is SocketIoPacket.Connect -> logger.debug { "socket.io connect: ${packet.data}" }
            is SocketIoPacket.Disconnect -> logger.debug { "socket.io disconnect" }
            is SocketIoPacket.Event -> when (val event = decodeCognigyEvent(json, packet)) {
                is OutputEvent -> output.send(event)
                else -> {}
            }
            is SocketIoPacket.Acknowledge -> logger.debug { "socket.io ack: id=${packet.acknowledgeId}, data=${packet.data}" }
            is SocketIoPacket.BinaryEvent -> logger.debug { "socket.io binary event: id=${packet.acknowledgeId}, name=${packet.name}, data=${packet.data}" }
            is SocketIoPacket.BinaryAcknowledge -> logger.debug { "socket.io binary ack: id=${packet.acknowledgeId}, data=${packet.data}" }
            is SocketIoPacket.BrokenPacket -> logger.warn(packet.t) { "received broken socket.io packet: ${packet.reason}, ${packet.packet}" }
        }
    }

    private companion object : KLoggable {
        override val logger = logger()
    }
}