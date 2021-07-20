package tel.schich.kognigy.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class OutputData(val text: String, val data: JsonElement, val traceId: String, val disableSensitiveLogging: Boolean, val source: String)

@Serializable
data class ErrorData(val error: JsonElement)

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
        data class Message(val data: OutputData) : Output()
        @Serializable
        @SerialName("error")
        data class Error(val data: ErrorData) : Output()

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
            @SerialName("cognigyStopFlow")
            STOP_FLOW,
            @SerialName("regular")
            REGULAR,
            @SerialName("error")
            ERROR,
        }

        companion object {
            const val NAME = "finalPing"
        }
    }

    @Serializable
    data class TriggeredElement(val id: String, val isDisableSensitiveLogging: Boolean, val result: Boolean?) : OutputEvent {
        companion object {
            const val NAME = "triggeredElement"
        }
    }

    @Serializable
    data class Exception(val error: JsonElement) : OutputEvent {
        companion object {
            const val NAME = "exception"
        }
    }

    data class BrokenEvent(val data: SocketIoPacket.Event, val reason: String, val t: Throwable?) : OutputEvent
}


fun decodeCognigyEvent(json: Json, packet: SocketIoPacket.Event): CognigyEvent = when {
    packet.arguments.isEmpty() -> CognigyEvent.BrokenEvent(packet, "no arguments given, exactly one needed", null)
    packet.arguments.size > 1 -> CognigyEvent.BrokenEvent(packet, "${packet.arguments.size} arguments given, exactly one needed", null)
    else -> {
        try {
            when (val name = packet.name) {
                CognigyEvent.ProcessInput.NAME -> json.decodeFromJsonElement<CognigyEvent.ProcessInput>(packet.arguments.first())
                CognigyEvent.Output.NAME -> json.decodeFromJsonElement<CognigyEvent.Output>(packet.arguments.first())
                CognigyEvent.TypingStatus.NAME -> json.decodeFromJsonElement<CognigyEvent.TypingStatus>(packet.arguments.first())
                CognigyEvent.FinalPing.NAME -> json.decodeFromJsonElement<CognigyEvent.FinalPing>(packet.arguments.first())
                CognigyEvent.TriggeredElement.NAME -> json.decodeFromJsonElement<CognigyEvent.TriggeredElement>(packet.arguments.first())
                CognigyEvent.Exception.NAME -> json.decodeFromJsonElement<CognigyEvent.Exception>(packet.arguments.first())
                else -> CognigyEvent.BrokenEvent(packet, "unknown event name: $name", null)
            }
        } catch (e: SerializationException) {
            CognigyEvent.BrokenEvent(packet, "failed to decode argument", e)
        }
    }
}

/**
 * Encodes the data into a socket.io message packet. In theory a SerializationException could be thrown,
 * it is very unlikely unless the entire project is misconfigured, since only known closed types are being encoded here.
 */
private inline fun <reified T: Any> data(json: Json, name: String, event: T) =
    SocketIoPacket.Event(null, null, name, listOf(json.encodeToJsonElement(event)))

fun encodeCognigyEvent(json: Json, event: CognigyEvent): SocketIoPacket = when (event) {
    is CognigyEvent.ProcessInput -> data(json, CognigyEvent.ProcessInput.NAME, event)
    is CognigyEvent.Output -> data(json, CognigyEvent.Output.NAME, event)
    is CognigyEvent.TypingStatus -> data(json, CognigyEvent.TypingStatus.NAME, event)
    is CognigyEvent.FinalPing -> data(json, CognigyEvent.FinalPing.NAME, event)
    is CognigyEvent.TriggeredElement -> data(json, CognigyEvent.TriggeredElement.NAME, event)
    is CognigyEvent.Exception -> data(json, CognigyEvent.Exception.NAME, event)
    is CognigyEvent.BrokenEvent -> event.data
}