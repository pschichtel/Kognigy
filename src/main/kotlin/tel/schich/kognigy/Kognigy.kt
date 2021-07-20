package tel.schich.kognigy

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mu.KLoggable
import tel.schich.kognigy.protocol.*
import tel.schich.kognigy.protocol.CognigyEvent.InputEvent
import tel.schich.kognigy.protocol.CognigyEvent.OutputEvent

data class Session(
    val endpointToken: String,
    val sessionId: String,
    val userId: String,
    val channelName: String,
    val source: String,
    val passthroughIp: String?,
    val output: Flow<Pair<OutputEvent, Long>>,
    private val encoder: (InputEvent, Long) -> Frame,
    private val wsSession: DefaultClientWebSocketSession,
) {
    suspend fun sendInput(
        text: String,
        data: JsonElement? = null,
        reloadFlow: Boolean = false,
        resetFlow: Boolean = false,
        resetState: Boolean = false,
        resetContext: Boolean = false,
    ) {
        val event = CognigyEvent.ProcessInput(
            URLToken = endpointToken,
            userId = userId,
            sessionId = sessionId,
            channel = channelName,
            source = source,
            passthroughIP = passthroughIp,
            reloadFlow = reloadFlow,
            resetFlow = resetFlow,
            resetState = resetState,
            resetContext = resetContext,
            data = data,
            text = text,
        )
        send(event)
    }

    suspend fun send(event: InputEvent) = wsSession.send(encoder(event, System.currentTimeMillis()))

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "")) {
        wsSession.close(closeReason)
        wsSession.cancel()
    }
}

class Kognigy(
    private val client: HttpClient,
    private val json: Json,
) {

    suspend fun connect(
        uri: Url,
        endpointToken: String,
        sessionId: String,
        userId: String,
        channelName: String,
        source: String,
        passthroughIp: String? = null
    ): Session {
        if (!(uri.protocol == URLProtocol.HTTP || uri.protocol == URLProtocol.HTTPS)) {
            throw IllegalArgumentException("Protocol must be http or https")
        }

        fun encodeInput(event: InputEvent, timestamp: Long): Frame =
            encodeEngineIoPacket(json, encodeSocketIoPacket(json, encodeCognigyEvent(json, event, timestamp)))

        val httpRequest = client.request<HttpStatement>(uri) {
            method = HttpMethod.Get
            url {
                protocol =
                    if (uri.protocol.isSecure()) URLProtocol.WSS
                    else URLProtocol.WS
                port = if (uri.port <= 0) protocol.defaultPort else uri.port
                encodedPath = "/socket.io/"
                parameter("transport", "websocket")
            }
        }
        val wsSession = httpRequest.receive<DefaultClientWebSocketSession>()
        val flow = wsSession.incoming
            .consumeAsFlow()
            .mapNotNull { frame ->
                val now = System.currentTimeMillis()
                processWebsocketFrame(frame, now) { wsSession.send(encodeEngineIoPacket(json, it)) }
            }

        return Session(
            endpointToken,
            sessionId,
            userId,
            channelName,
            source,
            passthroughIp,
            flow,
            ::encodeInput,
            wsSession,
        )
    }

    private suspend fun processWebsocketFrame(
        frame: Frame,
        timestamp: Long,
        sink: suspend (EngineIoPacket) -> Unit,
    ): Pair<OutputEvent, Long>? {
        when (frame) {
            is Frame.Text -> return processEngineIoPacket(frame, timestamp, sink)
            is Frame.Binary -> logger.warn { "websocket binary, unable to process binary data: $frame" }
            is Frame.Close -> logger.debug { "websocket close: $frame" }
            is Frame.Ping -> logger.debug { "websocket ping: $frame" }
            is Frame.Pong -> logger.debug { "websocket pong: $frame" }
        }
        return null
    }

    private suspend fun processEngineIoPacket(
        frame: Frame.Text,
        timestamp: Long,
        sink: suspend (EngineIoPacket) -> Unit,
    ): Pair<OutputEvent, Long>? {
        when (val packet = decodeEngineIoPacket(json, frame, timestamp)) {
            is EngineIoPacket.Open -> logger.debug("engine.io open: $packet")
            is EngineIoPacket.Close -> logger.debug("engine.io close")
            is EngineIoPacket.TextMessage -> return processSocketIoPacket(packet)
            is EngineIoPacket.Ping -> sink(EngineIoPacket.Pong)
            is EngineIoPacket.Pong -> logger.debug("engine.io pong")
            is EngineIoPacket.Upgrade -> logger.debug("engine.io upgrade")
            is EngineIoPacket.Noop -> logger.debug("engine.io noop")
            is EngineIoPacket.Error -> logger.debug(packet.t) { "received broken engine.io packet: ${packet.reason}, ${packet.data}" }
        }
        return null
    }

    private fun processSocketIoPacket(engineIoPacket: EngineIoPacket.TextMessage): Pair<OutputEvent, Long>? {
        when (val packet = decodeSocketIoPacket(json, engineIoPacket)) {
            is SocketIoPacket.Connect -> logger.debug { "socket.io connect: ${packet.data}" }
            is SocketIoPacket.Disconnect -> logger.debug { "socket.io disconnect" }
            is SocketIoPacket.Event -> processCognigyEvent(packet)
            is SocketIoPacket.Acknowledge -> logger.debug { "socket.io ack: id=${packet.acknowledgeId}, data=${packet.data}" }
            is SocketIoPacket.BinaryEvent -> logger.debug { "socket.io binary event: id=${packet.acknowledgeId}, name=${packet.name}, data=${packet.data}" }
            is SocketIoPacket.BinaryAcknowledge -> logger.debug { "socket.io binary ack: id=${packet.acknowledgeId}, data=${packet.data}" }
            is SocketIoPacket.BrokenPacket -> logger.warn(packet.t) { "received broken socket.io packet: ${packet.reason}, ${packet.packet}" }
        }
        return null
    }

    private fun processCognigyEvent(packet: SocketIoPacket.Event): Pair<OutputEvent, Long>? {
        return when (val event = decodeCognigyEvent(json, packet)) {
            is OutputEvent -> Pair(event, packet.timestamp)
            else -> null
        }
    }

    companion object : KLoggable {
        override val logger = logger()

        fun simple(
            connectTimeoutMillis: Long = 2000,
            requestTimeoutMillis: Long = 2000,
            socketTimeoutMillis: Long = 2000,
        ): Kognigy {
            val client = HttpClient {
                install(WebSockets)
                install(HttpTimeout) {
                    this.connectTimeoutMillis = connectTimeoutMillis
                    //this.requestTimeoutMillis = requestTimeoutMillis
                    //this.socketTimeoutMillis = socketTimeoutMillis
                }
            }

            val json = Json {
                encodeDefaults = true
            }

            return Kognigy(client, json)
        }
    }
}