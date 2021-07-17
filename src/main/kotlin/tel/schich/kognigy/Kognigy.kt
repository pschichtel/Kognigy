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
import tel.schich.kognigy.wire.*
import tel.schich.kognigy.wire.CognigyEvent.InputEvent
import tel.schich.kognigy.wire.CognigyEvent.OutputEvent
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class Session(
    val input: SendChannel<InputEvent>,
    val output: ReceiveChannel<OutputEvent>,
    private val wsSession: DefaultClientWebSocketSession,
) {
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

        val input = Channel<InputEvent>(Channel.UNLIMITED)
        val output = Channel<OutputEvent>(Channel.UNLIMITED)

        val scheme =
            if (uri.scheme.equals("http", false)) "ws"
            else "wss"

        val wsUri = URI(scheme, uri.userInfo, uri.host, uri.port, "/socket.io/", "transport=websocket", null).toString()

        return suspendCoroutine { continuation ->
            coroutineScope.launch {
                try {
                    client.ws(urlString = wsUri) {
                        continuation.resume(Session(input, output, this))
                        handle(this, input, output)
                    }
                } catch (e: Exception) {
                    logger.error("WebSocket connection failed!", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun handle(session: DefaultClientWebSocketSession, input: ReceiveChannel<InputEvent>, output: SendChannel<OutputEvent>) {
        suspend fun sink(packet: SocketIoPacket) {
            session.send(encodeSocketIoPacket(json, packet))
        }

        session.launch {
            sendCognigyEvents(input, ::sink)
        }

        receiveCognigyEvents(session.incoming, output, ::sink)
    }

    private suspend fun sendCognigyEvents(input: ReceiveChannel<InputEvent>, sink: suspend (SocketIoPacket) -> Unit) {
        for (event in input) {
            val frame = encodeCognigyFrame(json, CognigyFrame.Event(event))
            if (frame != null) {
                sink(frame)
            }
        }
    }

    private suspend fun receiveCognigyEvents(incoming: ReceiveChannel<Frame>, output: SendChannel<OutputEvent>, sink: suspend (SocketIoPacket) -> Unit) {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    when (val packet = parseSocketIoPacketFromText(json, frame.readText())) {
                        is SocketIoPacket.Open -> logger.info("socket.io open: $packet")
                        is SocketIoPacket.Close -> logger.info("socket.io close")
                        is SocketIoPacket.TextMessage -> {
                            when (val cognigyFrame = parseCognigyFrame(json, packet)) {
                                is CognigyFrame.Noop -> logger.info("cognigy noop")
                                is CognigyFrame.Event -> when (val event = cognigyFrame.event) {
                                    is OutputEvent -> output.send(event)
                                    else -> {}
                                }
                                is CognigyFrame.BrokenPacket -> {
                                    logger.warn(cognigyFrame.t) { "Received broken packet: ${cognigyFrame.reason}, ${cognigyFrame.packet}" }
                                }
                            }
                        }
                        is SocketIoPacket.Ping -> {
                            sink(SocketIoPacket.Pong)
                        }
                        is SocketIoPacket.Pong -> logger.info("socket.io pong")
                        is SocketIoPacket.Upgrade -> logger.info("socket.io upgrade")
                        is SocketIoPacket.Noop -> logger.info("socket.io noop")
                        is SocketIoPacket.Error -> logger.info(packet.t) { "received broken socket.io packet: ${packet.reason}, ${packet.data}" }
                    }
                }
                is Frame.Binary -> {
                    logger.warn("unable to process binary data!")
                }
                is Frame.Close,
                is Frame.Ping,
                is Frame.Pong -> {
                }
            }
        }
    }

    private companion object : KLoggable {
        override val logger = logger()
    }
}