package tel.schich.kognigy.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

sealed interface CognigyEvent {

    sealed interface InputEvent : CognigyEvent
    sealed interface OutputEvent : CognigyEvent

    @Serializable
    data class OutputData(val text: String, val data: JsonElement, val traceId: String, val disableSensitiveLogging: Boolean, val source: String)

    @Serializable
    data class ErrorData(val error: JsonElement)

    @Serializable
    data class ProcessInput(
        @SerialName("URLToken")
        val urlToken: String,
        val userId: String,
        val sessionId: String,
        val channel: String,
        val source: String,
        @SerialName("passthroughIP")
        val passthroughIp: String?,
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
    data class TypingStatus(
        val status: Status,
    ) : OutputEvent {
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
    data class FinalPing(
        val type: Type,
    ) : OutputEvent {

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
    data class TriggeredElement(
        val id: String,
        val isDisableSensitiveLogging: Boolean,
        val result: Boolean?,
    ) : OutputEvent {
        companion object {
            const val NAME = "triggeredElement"
        }
    }

    @Serializable
    data class Exception(
        val error: JsonElement,
    ) : OutputEvent {
        companion object {
            const val NAME = "exception"
        }
    }

    data class BrokenEvent(
        val data: SocketIoPacket.Event,
        val reason: String,
        val t: Throwable?,
    ) : OutputEvent

    companion object {
        fun decode(json: Json, packet: SocketIoPacket.Event): CognigyEvent = when {
            packet.arguments.isEmpty() -> BrokenEvent(packet, "no arguments given, exactly one needed", null)
            packet.arguments.size > 1 -> BrokenEvent(packet, "${packet.arguments.size} arguments given, exactly one needed", null)
            else -> {
                try {
                    when (val name = packet.name) {
                        ProcessInput.NAME -> json.decodeFromJsonElement<ProcessInput>(packet.arguments.first())
                        Output.NAME -> json.decodeFromJsonElement<Output>(packet.arguments.first())
                        TypingStatus.NAME -> json.decodeFromJsonElement<TypingStatus>(packet.arguments.first())
                        FinalPing.NAME -> json.decodeFromJsonElement<FinalPing>(packet.arguments.first())
                        TriggeredElement.NAME -> json.decodeFromJsonElement<TriggeredElement>(packet.arguments.first())
                        Exception.NAME -> json.decodeFromJsonElement<Exception>(packet.arguments.first())
                        else -> BrokenEvent(packet, "unknown event name: $name", null)
                    }
                } catch (e: SerializationException) {
                    BrokenEvent(packet, "failed to decode argument", e)
                }
            }
        }

        /**
         * Encodes the data into a socket.io message packet. In theory a SerializationException could be thrown,
         * it is very unlikely unless the entire project is misconfigured, since only known closed types are being encoded here.
         */
        private inline fun <reified T : Any> data(json: Json, name: String, event: T) =
            SocketIoPacket.Event(null, null, name, listOf(json.encodeToJsonElement(event)))

        fun encode(json: Json, event: CognigyEvent): SocketIoPacket = when (event) {
            is ProcessInput -> data(json, ProcessInput.NAME, event)
            is Output -> data(json, Output.NAME, event)
            is TypingStatus -> data(json, TypingStatus.NAME, event)
            is FinalPing -> data(json, FinalPing.NAME, event)
            is TriggeredElement -> data(json, TriggeredElement.NAME, event)
            is Exception -> data(json, Exception.NAME, event)
            is BrokenEvent -> event.data
        }
    }
}
