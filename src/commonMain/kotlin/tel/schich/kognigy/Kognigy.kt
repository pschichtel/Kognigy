package tel.schich.kognigy

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.isSecure
import io.ktor.utils.io.core.String
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.CognigyEvent.OutputEvent
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.SocketIoPacket

class Kognigy(
    engineFactory: HttpClientEngineFactory<*>,
    private val connectTimeoutMillis: Long = 2000,
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
                parameter("urlToken", session.endpointToken.value)
                parameter("sessionId", session.id.value)
                parameter("userId", session.userId.value)
                parameter("testMode", session.testMode.toString())
            }
        }

        val outputs = Channel<OutputEvent>(Channel.RENDEZVOUS)
        val onSocketIoConnected = CompletableDeferred<Unit>()
        val connection = KognigyConnection(session, outputs, wsSession, json, onSocketIoConnected)

        wsSession.incoming
            .consumeAsFlow()
            .mapNotNull { frame ->
                processWebsocketFrame(frame, connection)
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

        try {
            withTimeout(connectTimeoutMillis) {
                onSocketIoConnected.join()
            }
        } catch (e: TimeoutCancellationException) {
            wsSession.cancel(e)
            throw e
        }

        return connection
    }

    private suspend fun processWebsocketFrame(
        frame: Frame,
        connection: KognigyConnection,
    ): OutputEvent? {
        logger.trace { "Frame: " + String(frame.data) }
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, connection)
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
        connection: KognigyConnection,
    ): OutputEvent? {
        val packet = EngineIoPacket.decode(json, frame)
        logger.trace { "EngineIO packet: $packet" }
        when (packet) {
            is EngineIoPacket.Open -> {
                connection.setupPingTimer(packet.pingIntervalMillis, packet.pingTimeoutMillis)
                logger.debug { "engine.io open: $packet" }
            }
            is EngineIoPacket.Close -> logger.debug {
                "engine.io close"
            }
            is EngineIoPacket.BinaryMessage -> logger.debug {
                "engine.io binary frame"
            }
            is EngineIoPacket.TextMessage -> {
                return processSocketIoPacket(packet, connection)
            }
            is EngineIoPacket.Ping -> {
                connection.send(EngineIoPacket.Pong)
            }
            is EngineIoPacket.Pong -> {
                logger.debug { "engine.io pong" }
                connection.onPong()
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

    private fun processSocketIoPacket(
        engineIoPacket: EngineIoPacket.TextMessage,
        connection: KognigyConnection,
    ): OutputEvent? {
        val packet = SocketIoPacket.decode(json, engineIoPacket)
        logger.trace { "SocketIO packet: $packet" }
        when (packet) {
            is SocketIoPacket.Connect -> {
                logger.debug { "socket.io connect: ${packet.data}" }
                connection.onConnected()
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
