package tel.schich.kognigy

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.isSecure
import io.ktor.http.takeFrom
import io.ktor.utils.io.core.String
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.SocketIoPacket

class EarlyDisconnectException :
    Exception("The session got disconnected before receiving anything! Check your configuration!")

class UnableToConnectException(cause: TimeoutCancellationException) :
    Exception("The connection could not be established with the configured connect timeout!", cause)

private val receiveJobName = CoroutineName("kognigy-receive-job")

class Kognigy(
    engineFactory: HttpClientEngineFactory<*>,
    private val connectTimeoutMillis: Long = 2000,
    private val userAgent: String = "Kognigy",
    @Deprecated(message = "This is ignored, configure it in the engine directly", level = DeprecationLevel.WARNING)
    private val proxyConfig: ProxyConfig? = null,
    /**
     * Domain:
     * * `n > 0`: wait n millis for the endpoint-ready event, afterward assume ready
     * * `n = 0`: immediately assume ready
     * * `n < 0`: never assume ready
     */
    private val endpointReadyTimeoutMillis: Long = -1,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private val client = HttpClient(engineFactory) {
        engine {
            @Suppress("DEPRECATION")
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

    suspend fun connect(
        session: KognigySession,
        endpointReadyTimeoutMillis: Long = this.endpointReadyTimeoutMillis,
    ): KognigyConnection {
        val url = session.endpoint

        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) {
            "Protocol must be http or https"
        }

        val wsSession = client.webSocketSession {
            method = HttpMethod.Get
            url {
                takeFrom(url)
                protocol =
                    if (url.protocol.isSecure()) URLProtocol.WSS
                    else URLProtocol.WS
                encodedPath = "/socket.io/"
                parameters.append("EIO", "3")
                parameters.append("transport", "websocket")
                parameters.append("urlToken", session.endpointToken.value)
                parameters.append("sessionId", session.id.value)
                parameters.append("userId", session.userId.value)
                parameters.append("testMode", session.testMode.toString())
            }
        }

        val outputs = Channel<CognigyEvent.OutputEvent>(Channel.UNLIMITED)
        val connection = KognigyConnection(session, outputs, wsSession, json, endpointReadyTimeoutMillis)

        wsSession.launch(receiveJobName) {
            while (true) {
                val result = wsSession.incoming.receiveCatching()
                if (result.isClosed) {
                    outputs.close(result.exceptionOrNull())
                    break
                }
                processWebsocketFrame(result.getOrThrow(), connection)?.let {
                    outputs.send(it)
                }
            }
        }

        try {
            awaitConnected(wsSession, connection.connectionSuccessReason)
        } catch (e: Throwable) {
            connection.onConnectError(e)
            outputs.close(e)
            wsSession.close()
            throw e
        }

        return connection
    }

    private suspend fun awaitConnected(wsSession: WebSocketSession, onSocketIoConnected: Deferred<*>) {
        if (!wsSession.isActive) {
            throw EarlyDisconnectException()
        }
        try {
            withTimeout(connectTimeoutMillis) {
                onSocketIoConnected.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw UnableToConnectException(e)
        }
    }

    private suspend fun processWebsocketFrame(
        frame: Frame,
        connection: KognigyConnection,
    ): CognigyEvent.OutputEvent? {
        logger.trace { "Frame: " + String(frame.data) }
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, connection)
            is Frame.Binary -> logger.warn { "websocket binary, unable to process binary data: $frame" }
            is Frame.Close -> logger.trace { "websocket close: $frame" }
            is Frame.Ping -> logger.trace { "websocket ping: $frame" }
            is Frame.Pong -> logger.trace { "websocket pong: $frame" }
            else -> logger.error { "unknown websocket frame: $frame" }
        }
        return null
    }

    private suspend fun processEngineIoPacket(
        frame: Frame.Text,
        connection: KognigyConnection,
    ): CognigyEvent.OutputEvent? {
        val packet = EngineIoPacket.decode(json, frame)
        logger.trace { "EngineIO packet: $packet" }
        when (packet) {
            is EngineIoPacket.Open -> {
                logger.trace { "engine.io open: $packet" }
                connection.setupPingTimer(packet.pingIntervalMillis, packet.pingTimeoutMillis)
            }
            is EngineIoPacket.Close -> logger.trace {
                "engine.io close"
            }
            is EngineIoPacket.BinaryMessage -> logger.trace {
                "engine.io binary frame"
            }
            is EngineIoPacket.TextMessage -> {
                return processSocketIoPacket(packet, connection)
            }
            is EngineIoPacket.Ping -> {
                connection.send(EngineIoPacket.Pong, flush = true)
            }
            is EngineIoPacket.Pong -> {
                logger.trace { "engine.io pong" }
                connection.onPong()
            }
            is EngineIoPacket.Upgrade -> logger.trace {
                "engine.io upgrade"
            }
            is EngineIoPacket.Noop -> logger.trace {
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
    ): CognigyEvent.OutputEvent? {
        val packet = SocketIoPacket.decode(json, engineIoPacket)
        logger.trace { "SocketIO packet: $packet" }
        when (packet) {
            is SocketIoPacket.Connect -> {
                logger.trace { "socket.io connect: ${packet.data}" }
                connection.setupEndpointReadyTimeout()
            }
            is SocketIoPacket.ConnectError -> logger.error {
                "socket.io connectError: ${packet.data}"
            }
            is SocketIoPacket.Disconnect -> logger.trace {
                "socket.io disconnect"
            }
            is SocketIoPacket.Event -> when (val event = CognigyEvent.decode(json, packet)) {
                is CognigyEvent.OutputEvent -> return event
                is CognigyEvent.EndpointReady -> {
                    connection.onEndpointReady()
                }
                is CognigyEvent.ProcessInput -> {}
            }
            is SocketIoPacket.Acknowledge -> logger.trace {
                "socket.io ack: id=${packet.acknowledgeId}, data=${packet.data}"
            }
            is SocketIoPacket.BinaryEvent -> logger.trace {
                val data = packet.data.toHexString()
                "socket.io binary event: id=${packet.acknowledgeId}, name=${packet.name}, data=$data"
            }
            is SocketIoPacket.BinaryAcknowledge -> logger.trace {
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
