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
import io.ktor.utils.io.core.String
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
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

class Kognigy(
    engineFactory: HttpClientEngineFactory<*>,
    private val connectTimeoutMillis: Long = 2000,
    private val userAgent: String = "Kognigy",
    private val proxyConfig: ProxyConfig? = null,
    @Deprecated(message = "This should not be used", level = DeprecationLevel.WARNING)
    private val endpointReadyTimeoutOfShameMillis: Long = 0,
) {
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

        val outputs = Channel<CognigyEvent.OutputEvent>(Channel.RENDEZVOUS)
        val onSocketIoConnected = CompletableDeferred<Unit>()
        val connection = KognigyConnection(session, outputs, wsSession, json, onSocketIoConnected)

        val receiveJob = connection.coroutineScope.launch {
            if (!wsSession.isActive) {
                // the websocket became inactive before the first receive attempt
                onSocketIoConnected.completeExceptionally(EarlyDisconnectException())
                return@launch
            }
            while (isActive) {
                val result = wsSession.incoming.receiveCatching()
                if (result.isFailure) {
                    val cause = result.exceptionOrNull()
                    if (result.isClosed) {
                        if (cause != null) {
                            logger.warn(cause) { "The channel closed with an exception!" }
                        }
                        break
                    } else {
                        logger.error(cause) { "Failed to receive from websocket!" }
                    }
                } else {
                    processWebsocketFrame(result.getOrThrow(), connection)?.let {
                        outputs.send(it)
                    }
                }
            }
            val closeReason = wsSession.closeReason.await()
            logger.info { "Websocket closed: $closeReason" }
        }
        receiveJob.invokeOnCompletion { cause ->
            // propagate the cancellation to the websocket
            when (cause) {
                null -> wsSession.cancel()
                is CancellationException -> wsSession.cancel(cause)
                else -> wsSession.cancel(CancellationException("The receive job completed with an error!", cause))
            }
        }

        try {
            withTimeout(connectTimeoutMillis) {
                onSocketIoConnected.await()
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "Timed out while waiting for socket.io's connected event after ${connectTimeoutMillis}ms!" }
            receiveJob.cancel(e)
            throw e
        }

        return connection
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
                connection.send(EngineIoPacket.Pong)
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
                @Suppress("DEPRECATION")
                connection.setupEndpointReadyTimeout(endpointReadyTimeoutOfShameMillis) {
                    logger.warn { "The endpoint did not become ready within $endpointReadyTimeoutOfShameMillis ms, assuming it's ready..." }
                }
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
                    connection.onConnectionReady()
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
