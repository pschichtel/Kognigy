package tel.schich.kognigy

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.isSecure
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mu.KotlinLogging
import tel.schich.kognigy.protocol.ChannelName
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.CognigyEvent.InputEvent
import tel.schich.kognigy.protocol.CognigyEvent.OutputEvent
import tel.schich.kognigy.protocol.EndpointToken
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.SessionId
import tel.schich.kognigy.protocol.SocketIoPacket
import tel.schich.kognigy.protocol.Source
import tel.schich.kognigy.protocol.UserId

class Data(val data: ByteArray) {
    fun toHexString() = data.joinToString(" ") { byte ->
        byte.toUByte().toString(radix = 16).uppercase().padStart(length = 2, padChar = '0')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        val otherData = (other as? Data)?.data ?: return false

        return data.contentEquals(otherData)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}

class PingTimeoutException(message: String) : CancellationException(message)

private val logger = KotlinLogging.logger {}

@Serializable
data class KognigySession(
    val id: SessionId,
    @Serializable(with = UrlSerializer::class)
    val endpoint: Url,
    val endpointToken: EndpointToken,
    val userId: UserId,
    val channelName: ChannelName? = null,
    val source: Source = Source.Device,
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
        if (!wsSession.isActive) {
            error("Not connected!")
        }
        wsSession.send(encoder(event))
        if (flush) {
            wsSession.flush()
        }
    }

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "")) {
        wsSession.close(closeReason)
    }
}

class Kognigy(
    engineFactory: HttpClientEngineFactory<*>,
    connectTimeoutMillis: Long = 2000,
    private val userAgent: String = "Kognigy",
    private val proxyConfig: ProxyConfig? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val pingTimer = atomic<Job?>(null)
    private val pongTimeout = atomic<Job?>(null)

    private val client = HttpClient(engineFactory) {
        engine {
            proxy = proxyConfig
        }
        install(WebSockets)
        install(HttpTimeout) {
            this.connectTimeoutMillis = connectTimeoutMillis
        }
        install(UserAgent) {
            agent = userAgent
        }
    }

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
                parameter("EIO", "3")
                parameter("transport", "websocket")
            }
        }

        val outputs = Channel<OutputEvent>(Channel.UNLIMITED)

        suspend fun setupPingTimer(intervalMillis: Long, timeoutMillis: Long) {
            val job = timer(intervalMillis, intervalMillis)
                .onEach {
                    wsSession.send(EngineIoPacket.encode(json, EngineIoPacket.Ping))
                    val timeoutMessage = "engine.io pong didn't arrive for $timeoutMillis ms!"
                    @Suppress("LoopWithTooManyJumpStatements")
                    while (true) {
                        if (pongTimeout.value != null) {
                            break
                        }

                        val newJob = wsSession.launch(start = CoroutineStart.LAZY) {
                            delay(timeoutMillis)
                            val reason = PingTimeoutException(timeoutMessage)
                            wsSession.cancel(reason)
                        }
                        if (pongTimeout.compareAndSet(null, newJob)) {
                            newJob.start()
                            break
                        } else {
                            newJob.cancel()
                        }
                    }
                }
                .launchIn(wsSession)
            pingTimer.getAndSet(job)?.cancel()
        }

        wsSession.incoming
            .consumeAsFlow()
            .mapNotNull { frame ->
                processWebsocketFrame(frame, ::setupPingTimer) { wsSession.send(EngineIoPacket.encode(json, it)) }
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
        setupPing: suspend (Long, Long) -> Unit,
        sink: suspend (EngineIoPacket) -> Unit,
    ): OutputEvent? {
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, setupPing, sink)
            is Frame.Binary -> logger.warn { "websocket binary, unable to process binary data: $frame" }
            is Frame.Close -> logger.debug { "websocket close: $frame" }
            is Frame.Ping -> logger.debug { "websocket ping: $frame" }
            is Frame.Pong -> logger.debug { "websocket pong: $frame" }
            else -> logger.error { "unknown websocket frame: $frame" }
        }
        return null
    }

    private suspend fun processEngineIoPacket(
        frame: Frame.Text,
        setupPing: suspend (Long, Long) -> Unit,
        sink: suspend (EngineIoPacket) -> Unit,
    ): OutputEvent? {
        when (val packet = EngineIoPacket.decode(json, frame)) {
            is EngineIoPacket.Open -> {
                setupPing(packet.pingIntervalMillis, packet.pingTimeoutMillis)
                logger.debug { "engine.io open: $packet" }
            }
            is EngineIoPacket.Close -> logger.debug {
                "engine.io close"
            }
            is EngineIoPacket.BinaryMessage -> logger.debug {
                "engine.io binary frame"
            }
            is EngineIoPacket.TextMessage -> {
                return processSocketIoPacket(packet)
            }
            is EngineIoPacket.Ping -> {
                sink(EngineIoPacket.Pong)
            }
            is EngineIoPacket.Pong -> {
                logger.debug { "engine.io pong" }
                val timeout = pongTimeout.getAndSet(null)
                if (timeout != null) {
                    logger.debug { "engine.io ping timeout cancelled!" }
                    timeout.cancel()
                }
            }
            is EngineIoPacket.Upgrade -> logger.debug {
                "engine.io upgrade"
            }
            is EngineIoPacket.Noop -> logger.debug {
                "engine.io noop"
            }
            is EngineIoPacket.Error -> logger.error(packet.t) {
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
            is SocketIoPacket.ConnectError -> logger.debug {
                "socket.io connectError: ${packet.data}"
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
                val data = packet.data.toHexString()
                "socket.io binary event: id=${packet.acknowledgeId}, name=${packet.name}, data=$data"
            }
            is SocketIoPacket.BinaryAcknowledge -> logger.debug {
                "socket.io binary ack: id=${packet.acknowledgeId}, data=${packet.data?.toHexString()}"
            }
            is SocketIoPacket.BrokenPacket -> logger.error(packet.t) {
                "received broken socket.io packet: ${packet.reason}, ${packet.packet}"
            }
        }
        return null
    }
}
