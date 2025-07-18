package tel.schich.kognigy

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.isSecure
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.CognigyEvent.ProtocolError.Subject.EngineIo
import tel.schich.kognigy.protocol.CognigyEvent.ProtocolError.Subject.SocketIo
import tel.schich.kognigy.protocol.CognigyEvent.ProtocolError.Subject.Websocket
import tel.schich.kognigy.protocol.EngineIoPacket
import tel.schich.kognigy.protocol.SocketIoPacket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class EarlyDisconnectException :
    Exception("The session got disconnected before receiving anything! Check your configuration!")

class UnableToConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val receiveJobName = CoroutineName("kognigy-receive-job")

internal val json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

class Kognigy(
    engineFactory: HttpClientEngineFactory<*>,
    private val connectTimeout: Duration = 2.seconds,
    private val userAgent: String = "Kognigy",
    /**
     * Domain:
     * * `n > 0`: wait the duration for the endpoint-ready event, afterward assume ready
     * * `n = 0`: immediately assume ready
     * * `n = infinite`: never assume ready
     */
    private val endpointReadyTimeout: Duration,
    private val sendAcknowledgements: Boolean = true,
) {

    private val client = HttpClient(engineFactory) {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
        }
        install(UserAgent) {
            agent = userAgent
        }
    }

    suspend fun connect(
        session: KognigySession,
        endpointReadyTimeout: Duration = this.endpointReadyTimeout,
    ): KognigyConnection {
        val url = session.endpoint

        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS) {
            "Protocol must be http or https"
        }

        val wsSession = try {
            withTimeoutOrNull(connectTimeout) {
                client.webSocketSession {
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
                        parameters.append("emitWithAck", sendAcknowledgements.toString())
                    }
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw UnableToConnectException(message = "The TCP connection timed out!", cause = e)
        }

        if (wsSession == null) {
            throw UnableToConnectException(message = "The HTTP request timed out!")
        }

        val outputs = Channel<CognigyEvent.OutputEvent>(Channel.UNLIMITED)
        val connection = KognigyConnection(session, outputs, wsSession, endpointReadyTimeout)

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

    private suspend fun <T : Any> awaitConnected(wsSession: WebSocketSession, onSocketIoConnected: Deferred<T>) {
        if (!wsSession.isActive) {
            throw EarlyDisconnectException()
        }

        val result = withTimeoutOrNull(connectTimeout) {
            onSocketIoConnected.await()
        }
        if (result == null) {
            throw UnableToConnectException(message = "The endpoint did not become ready in time!")
        }
    }

    private suspend fun processWebsocketFrame(
        frame: Frame,
        connection: KognigyConnection,
    ): CognigyEvent.OutputEvent? {
        logger.trace { "Frame: " + frame.data.decodeToString() }
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, connection)
            is Frame.Binary -> return CognigyEvent.ProtocolError(
                subject = Websocket(frame),
                message = "websocket binary, unable to process binary data: $frame",
                t = null,
            )
            is Frame.Close -> logger.trace { "websocket close: $frame" }
            is Frame.Ping -> logger.trace { "websocket ping: $frame" }
            is Frame.Pong -> logger.trace { "websocket pong: $frame" }
            else -> return CognigyEvent.ProtocolError(
                subject = Websocket(frame),
                message = "unknown websocket frame: $frame",
                t = null,
            )
        }
        return null
    }

    private suspend fun processEngineIoPacket(
        frame: Frame.Text,
        connection: KognigyConnection,
    ): CognigyEvent.OutputEvent? {
        val packet = EngineIoPacket.decode(frame)
        logger.trace { "EngineIO packet: $packet" }
        when (packet) {
            is EngineIoPacket.Open -> {
                logger.trace { "engine.io open: $packet" }
                connection.setupPingTimer(packet.pingInterval, packet.pingTimeout)
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
            is EngineIoPacket.Error -> return CognigyEvent.ProtocolError(
                subject = EngineIo(packet),
                message = "received broken engine.io packet: ${packet.reason}, ${packet.data}",
                t = packet.t,
            )
        }
        return null
    }

    private suspend fun processSocketIoPacket(
        engineIoPacket: EngineIoPacket.TextMessage,
        connection: KognigyConnection,
    ): CognigyEvent.OutputEvent? {
        val packet = SocketIoPacket.decode(engineIoPacket)
        logger.trace { "SocketIO packet: $packet" }
        when (packet) {
            is SocketIoPacket.Connect -> {
                logger.trace { "socket.io connect: ${packet.data}" }
                connection.setupEndpointReadyTimeout()
            }
            is SocketIoPacket.ConnectError -> {
                logger.error { "socket.io connectError: ${packet.data}" }
                connection.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Received connect error: ${packet.data}"))
                return null
            }
            is SocketIoPacket.Disconnect -> logger.trace {
                "socket.io disconnect"
            }
            is SocketIoPacket.Event -> {
                if (sendAcknowledgements && packet.acknowledgeId != null) {
                    connection.send(
                        SocketIoPacket.Acknowledge(
                            packet.namespace,
                            packet.acknowledgeId,
                            data = JsonArray(emptyList()),
                        ),
                        flush = true,
                    )
                }
                when (val event = CognigyEvent.decode(packet)) {
                    is CognigyEvent.OutputEvent -> return event
                    is CognigyEvent.EndpointReady -> {
                        connection.onEndpointReady()
                    }
                    is CognigyEvent.ProcessInput -> {}
                }
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
            is SocketIoPacket.BrokenPacket -> return CognigyEvent.ProtocolError(
                subject = SocketIo(packet),
                message = "received broken socket.io packet: ${packet.reason}, ${packet.packet}",
                t = packet.t,
            )
        }
        return null
    }

    private companion object {
        private val logger = KotlinLogging.logger("Kognigy")
    }
}
