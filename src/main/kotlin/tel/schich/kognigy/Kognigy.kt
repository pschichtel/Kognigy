package tel.schich.kognigy

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.features.HttpTimeout
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.webSocketSession
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.close
import io.ktor.http.isSecure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mu.KLoggable
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.CognigyEvent.InputEvent
import tel.schich.kognigy.protocol.CognigyEvent.OutputEvent
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.SocketIoPacket
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class KognigySession(
    val id: String,
    @Serializable(with = UrlSerializer::class)
    val endpoint: Url,
    val endpointToken: String,
    val userId: String,
    val channelName: String,
    val source: String,
    val passthroughIp: String? = null,
)

private class UrlSerializer : KSerializer<Url> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Url", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Url = Url(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Url) = encoder.encodeString(value.toString())
}

data class KognigyConnection(
    val session: KognigySession,
    val output: ReceiveChannel<OutputEvent>,
    private val encoder: (InputEvent) -> Frame,
    private val wsSession: WebSocketSession,
) {
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

    suspend fun send(event: InputEvent, flush: Boolean = false) {
        wsSession.send(encoder(event))
        if (flush) {
            wsSession.flush()
        }
    }

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "")) {
        wsSession.close(closeReason)
    }
}

class Kognigy(
    private val client: HttpClient,
    private val json: Json,
    private val pingIntervalMillis: Long,
) {
    private fun timer(initialDelayMillis: Long, fixedDelayMillis: Long): Flow<Unit> = flow {
        delay(initialDelayMillis)
        while (true) {
            emit(Unit)
            delay(fixedDelayMillis)
        }
    }

    suspend fun connect(session: KognigySession): KognigyConnection {
        val url = session.endpoint

        if (!(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS)) {
            throw IllegalArgumentException("Protocol must be http or https")
        }

        fun encodeInput(event: InputEvent): Frame =
            EngineIoPacket.encode(json, SocketIoPacket.encode(json, CognigyEvent.encode(json, event)))

        val wsSession = client.webSocketSession {
            method = HttpMethod.Get
            url {
                protocol =
                    if (url.protocol.isSecure()) URLProtocol.WSS
                    else URLProtocol.WS
                host = url.host
                port = url.port
                encodedPath = "/socket.io/"
                parameter("transport", "websocket")
            }
        }

        val outputs = Channel<OutputEvent>(Channel.UNLIMITED)

        val pingCounter = AtomicInteger(0)

        if (pingIntervalMillis > 0) {
            timer(pingIntervalMillis, pingIntervalMillis)
                .onEach {
                    wsSession.send(EngineIoPacket.encode(json, EngineIoPacket.Ping))
                    if (pingCounter.getAndIncrement() != 0) {
                        throw TimeoutException("engine.io pong didn't arrive for $pingIntervalMillis ms!")
                    }
                }
                .launchIn(wsSession)
        }

        wsSession.incoming
            .consumeAsFlow()
            .mapNotNull { frame ->
                processWebsocketFrame(frame, pingCounter) { wsSession.send(EngineIoPacket.encode(json, it)) }
            }
            .onEach(outputs::send)
            .onCompletion { cause ->
                if (cause is CancellationException) {
                    outputs.close()
                } else {
                    outputs.close(cause)
                }
            }
            .launchIn(wsSession)

        return KognigyConnection(session, outputs, ::encodeInput, wsSession)
    }

    private suspend fun processWebsocketFrame(
        frame: Frame,
        pingCounter: AtomicInteger,
        sink: suspend (EngineIoPacket) -> Unit,
    ): OutputEvent? {
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, pingCounter, sink)
            is Frame.Binary -> logger.warn { "websocket binary, unable to process binary data: $frame" }
            is Frame.Close -> logger.debug { "websocket close: $frame" }
            is Frame.Ping -> logger.debug { "websocket ping: $frame" }
            is Frame.Pong -> logger.debug { "websocket pong: $frame" }
        }
        return null
    }

    private suspend fun processEngineIoPacket(
        frame: Frame.Text,
        pingCounter: AtomicInteger,
        sink: suspend (EngineIoPacket) -> Unit,
    ): OutputEvent? {
        when (val packet = EngineIoPacket.decode(json, frame)) {
            is EngineIoPacket.Open -> logger.debug {
                "engine.io open: $packet"
            }
            is EngineIoPacket.Close -> logger.debug {
                "engine.io close"
            }
            is EngineIoPacket.TextMessage -> {
                return processSocketIoPacket(packet)
            }
            is EngineIoPacket.Ping -> {
                sink(EngineIoPacket.Pong)
            }
            is EngineIoPacket.Pong -> {
                logger.debug { "engine.io pong" }
                pingCounter.decrementAndGet()
            }
            is EngineIoPacket.Upgrade -> logger.debug {
                "engine.io upgrade"
            }
            is EngineIoPacket.Noop -> logger.debug {
                "engine.io noop"
            }
            is EngineIoPacket.Error -> logger.debug(packet.t) {
                "received broken engine.io packet: ${packet.reason}, ${packet.data}"
            }
        }
        return null
    }

    private fun processSocketIoPacket(engineIoPacket: EngineIoPacket.TextMessage): OutputEvent? {
        when (val packet = SocketIoPacket.decode(json, engineIoPacket)) {
            is SocketIoPacket.Connect -> logger.debug {
                "socket.io connect: ${packet.data}"
            }
            is SocketIoPacket.Disconnect -> logger.debug {
                "socket.io disconnect"
            }
            is SocketIoPacket.Event -> when (val event = CognigyEvent.decode(json, packet)) {
                is OutputEvent -> return event
                else -> {}
            }
            is SocketIoPacket.Acknowledge -> logger.debug {
                "socket.io ack: id=${packet.acknowledgeId}, data=${packet.data}"
            }
            is SocketIoPacket.BinaryEvent -> logger.debug {
                "socket.io binary event: id=${packet.acknowledgeId}, name=${packet.name}, data=${packet.data}"
            }
            is SocketIoPacket.BinaryAcknowledge -> logger.debug {
                "socket.io binary ack: id=${packet.acknowledgeId}, data=${packet.data}"
            }
            is SocketIoPacket.BrokenPacket -> logger.warn(packet.t) {
                "received broken socket.io packet: ${packet.reason}, ${packet.packet}"
            }
        }
        return null
    }

    companion object : KLoggable {
        override val logger = logger()

        fun <T : HttpClientEngineConfig> simple(
            engineFactory: HttpClientEngineFactory<T>,
            connectTimeoutMillis: Long = 2000,
            pingIntervalMillis: Long = 25000,
            customize: HttpClientConfig<T>.() -> Unit = {},
        ): Kognigy {
            val client = HttpClient(engineFactory) {
                install(WebSockets)
                install(HttpTimeout) {
                    this.connectTimeoutMillis = connectTimeoutMillis
                }

                customize()
            }

            val json = Json {
                encodeDefaults = true
            }

            return Kognigy(client, json, pingIntervalMillis)
        }
    }
}
