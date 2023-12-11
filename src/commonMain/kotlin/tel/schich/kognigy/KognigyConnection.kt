package tel.schich.kognigy

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import mu.KotlinLogging
import tel.schich.kognigy.protocol.CognigyEvent

data class KognigyConnection(
    val session: KognigySession,
    val output: ReceiveChannel<CognigyEvent.OutputEvent>,
    private val encoder: (CognigyEvent.InputEvent) -> Frame,
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

    suspend fun send(event: CognigyEvent.InputEvent, flush: Boolean = false) {
        val encodedEvent = encoder(event)

        logger.info {
            val prefix = (event as? CognigyEvent.ProcessInput)?.sessionId?.value?.let { "$it - " }
            "${prefix}Sending cognigy event: ${encodedEvent.data.map { it.toInt().toChar() }.joinToString("")}"
        }
        wsSession.send(encodedEvent)
        if (flush) {
            wsSession.flush()
        }
    }

    suspend fun close(closeReason: CloseReason = CloseReason(CloseReason.Codes.GOING_AWAY, "")) {
        wsSession.close(closeReason)
    }

    private companion object {
        private val logger = KotlinLogging.logger("KognigyConnection")
    }
}