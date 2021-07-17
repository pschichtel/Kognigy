package tel.schich.kognigy.wire

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import tel.schich.kognigy.wire.CognigyFrame.BrokenPacket
import tel.schich.kognigy.wire.CognigyFrame.Noop

sealed interface CognigyFrame {
    object Noop : CognigyFrame
    data class Event(val event: CognigyEvent) : CognigyFrame
    data class BrokenPacket(val packet: SocketIoPacket, val reason: String, val t: Throwable?) : CognigyFrame
}

@Serializable
data class OutputData(val text: String, val data: JsonElement, val traceId: String, val disableSensitiveLogging: Boolean, val source: String)

sealed interface CognigyEvent {

    sealed interface InputEvent : CognigyEvent
    sealed interface OutputEvent : CognigyEvent

    @Serializable
    data class ProcessInput(
        val URLToken: String,
        val userId: String,
        val sessionId: String,
        val channel: String,
        val source: String,
        val passthroughIP: String?,
        val reloadFlow: Boolean,
        val resetFlow: Boolean,
        val resetState: Boolean,
        val resetContext: Boolean,
        val text: String?,
        val data: JsonElement?,
    ) : InputEvent {
        companion object {
            const val NAME = "processInput"
        }
    }

    @Serializable
    sealed class Output : OutputEvent {
        @Serializable
        @SerialName("output")
        data class OutputOutput(val data: OutputData) : Output()

        companion object {
            const val NAME = "output"
        }
    }

    @Serializable
    data class TypingStatus(val status: Status) : OutputEvent {

        @Serializable
        enum class Status {
            @SerialName("typingOn")
            ON,
            @SerialName("typingOff")
            OFF,
        }

        companion object {
            const val NAME = "typingStatus"
        }
    }

    @Serializable
    data class FinalPing(val type: Type) : OutputEvent {

        @Serializable
        enum class Type {
            @SerialName("regular")
            REGULAR,
        }

        companion object {
            const val NAME = "finalPing"
        }
    }

    @Serializable
    data class UnknownEvent(val data: String) : OutputEvent
}


fun parseCognigyFrame(json: Json, packet: SocketIoPacket.TextMessage) = when (val id = packet.data[0]) {
    '0' -> Noop
    '2' -> {
        try {
            val (name, data) = json.decodeFromString<JsonArray>(packet.data.substring(1))
            if (name is JsonPrimitive && name.isString) {
                val event = when (name.content) {
                    CognigyEvent.ProcessInput.NAME -> json.decodeFromJsonElement<CognigyEvent.ProcessInput>(data)
                    CognigyEvent.Output.NAME -> json.decodeFromJsonElement<CognigyEvent.Output>(data)
                    CognigyEvent.TypingStatus.NAME -> json.decodeFromJsonElement<CognigyEvent.TypingStatus>(data)
                    CognigyEvent.FinalPing.NAME -> json.decodeFromJsonElement<CognigyEvent.FinalPing>(data)
                    else -> CognigyEvent.UnknownEvent(packet.data)
                }
                CognigyFrame.Event(event)
            } else BrokenPacket(packet, "first array entry was not a string: $name", null)
        } catch (e: SerializationException) {
            BrokenPacket(packet, "json parsing failed", e)
        }
    }
    else -> BrokenPacket(packet, "unknown type: $id", null)
}

/**
 * Encodes the data into a socket.io message packet. In theory a SerializationException could be thrown,
 * it is very unlikely unless the entire project is misconfigured, since only known closed types are being encoded here.
 */
private inline fun <reified T: Any> data(json: Json, name: String, event: T) =
    SocketIoPacket.TextMessage("2${json.encodeToString(listOf(JsonPrimitive(name), json.encodeToJsonElement(event)))}")

fun encodeCognigyFrame(json: Json, frame: CognigyFrame): SocketIoPacket = when (frame) {
    is Noop -> SocketIoPacket.TextMessage("0")
    is CognigyFrame.Event -> {
        try {
            when (val event = frame.event) {
                is CognigyEvent.ProcessInput -> data(json, CognigyEvent.ProcessInput.NAME, event)
                is CognigyEvent.Output -> data(json, CognigyEvent.Output.NAME, event)
                is CognigyEvent.TypingStatus -> data(json, CognigyEvent.TypingStatus.NAME, event)
                is CognigyEvent.FinalPing -> data(json, CognigyEvent.FinalPing.NAME, event)
                is CognigyEvent.UnknownEvent -> SocketIoPacket.TextMessage(event.data)
            }
        } catch (e: SerializationException) {
            KotlinLogging.logger {}.error(e) { "failed to encode a cognigy frame: $frame" }
            throw e
        }
    }
    is BrokenPacket -> frame.packet
}