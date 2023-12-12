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
import io.ktor.http.encodedPath
import io.ktor.http.isSecure
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.CognigyEvent.InputEvent
import tel.schich.kognigy.protocol.CognigyEvent.OutputEvent
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.PingTimeoutException
import tel.schich.kognigy.protocol.SocketIoPacket

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

    suspend fun connect(session: KognigySession): KognigyConnection {
        val url = session.endpoint

        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) {
            "Protocol must be http or https"
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
        var pingTimer: Job? = null
        var pongTimeout: Job? = null

        suspend fun setupPingTimer(intervalMillis: Long, timeoutMillis: Long) {
            pingTimer?.cancel()
            pingTimer = wsSession.launch {
                while (true) {
                    delay(intervalMillis)
                    wsSession.send(EngineIoPacket.encode(json, EngineIoPacket.Ping))
                    val timeoutMessage = "engine.io pong didn't arrive for $timeoutMillis ms!"
                    if (pongTimeout == null) {
                        pongTimeout = wsSession.launch {
                            delay(timeoutMillis)
                            val reason = PingTimeoutException(timeoutMessage)
                            wsSession.cancel(reason)
                        }
                    }
                }
            }
        }

        fun onPong() {
            pongTimeout?.cancel()
            pongTimeout = null
        }

        wsSession.incoming
            .consumeAsFlow()
            .mapNotNull { frame ->
                processWebsocketFrame(frame, ::setupPingTimer, ::onPong) {
                    wsSession.send(EngineIoPacket.encode(json, it))
                }
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
        onPong: () -> Unit,
        sink: suspend (EngineIoPacket) -> Unit,
    ): OutputEvent? {
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, setupPing, onPong, sink)
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
        onPong: () -> Unit,
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
                onPong()
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

    private companion object {
        private val logger = KotlinLogging.logger("Kognigy")
    }
}
