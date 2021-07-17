package tel.schich.kognigy.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

sealed interface CognigyFrame {
    object Noop : CognigyFrame
    data class Event(val event: CognigyEvent) : CognigyFrame
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
}


fun parseCognigyFrame(json: Json, payload: String) = when (payload[0]) {
    '0' -> CognigyFrame.Noop
    '2' -> {
        val (name, data) = json.decodeFromString<JsonArray>(payload.substring(1))
        if (name is JsonPrimitive && name.isString) {
            val event = when (name.content) {
                CognigyEvent.ProcessInput.NAME -> json.decodeFromJsonElement<CognigyEvent.ProcessInput>(data)
                CognigyEvent.Output.NAME -> json.decodeFromJsonElement<CognigyEvent.Output>(data)
                CognigyEvent.TypingStatus.NAME -> json.decodeFromJsonElement<CognigyEvent.TypingStatus>(data)
                CognigyEvent.FinalPing.NAME -> json.decodeFromJsonElement<CognigyEvent.FinalPing>(data)
                else -> null
            }
            event?.let(CognigyFrame::Event)
        } else null
    }
    else -> null
}

fun encodeCognigyFrame(json: Json, frame: CognigyFrame) = when (frame) {
    is CognigyFrame.Noop -> "0"
    is CognigyFrame.Event -> {
        val payload = when (val event = frame.event) {
            is CognigyEvent.ProcessInput -> listOf(JsonPrimitive(CognigyEvent.ProcessInput.NAME), json.encodeToJsonElement(event))
            is CognigyEvent.Output -> listOf(JsonPrimitive(CognigyEvent.Output.NAME), json.encodeToJsonElement(event))
            is CognigyEvent.TypingStatus -> listOf(JsonPrimitive(CognigyEvent.TypingStatus.NAME), json.encodeToJsonElement(event))
            is CognigyEvent.FinalPing -> listOf(JsonPrimitive(CognigyEvent.FinalPing.NAME), json.encodeToJsonElement(event))
        }
        "2${json.encodeToString(payload)}"
    }
}