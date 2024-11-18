package tel.schich.kognigy.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import tel.schich.parserkombinator.ParserResult.Error
import tel.schich.parserkombinator.ParserResult.Ok
import tel.schich.parserkombinator.StringSlice
import tel.schich.parserkombinator.andThenIgnore
import tel.schich.parserkombinator.concat
import tel.schich.parserkombinator.flatMap
import tel.schich.parserkombinator.map
import tel.schich.parserkombinator.optional
import tel.schich.parserkombinator.parseEntirely
import tel.schich.parserkombinator.take
import tel.schich.parserkombinator.takeWhile

/**
 * Based on: [github.com/socketio/socket.io-protocol](https://github.com/socketio/socket.io-protocol)
 */
sealed interface SocketIoPacket {

    sealed interface Namespaced {
        val namespace: String
    }

    data class Connect(
        override val namespace: String,
        val data: JsonObject?,
    ) : SocketIoPacket, Namespaced

    data class Disconnect(
        override val namespace: String,
    ) : SocketIoPacket, Namespaced

    data class Event(
        override val namespace: String,
        val acknowledgeId: Int?,
        val name: String,
        val arguments: List<JsonElement>,
    ) : SocketIoPacket, Namespaced

    data class Acknowledge(
        override val namespace: String,
        val acknowledgeId: Int,
        val data: JsonArray?,
    ) : SocketIoPacket, Namespaced

    data class ConnectError(
        override val namespace: String,
        val data: Data?,
    ) : SocketIoPacket, Namespaced {
        data class Data(val message: String, val data: JsonElement?)
    }

    data class BinaryEvent(
        override val namespace: String,
        val acknowledgeId: Int?,
        val name: String,
        val data: Data,
    ) : SocketIoPacket, Namespaced

    data class BinaryAcknowledge(
        override val namespace: String,
        val acknowledgeId: Int,
        val data: Data?,
    ) : SocketIoPacket, Namespaced

    data class BrokenPacket(
        val packet: String,
        val reason: String,
        val t: Throwable?,
    ) : SocketIoPacket

    companion object {
        fun decode(json: Json, packet: EngineIoPacket.TextMessage): SocketIoPacket {
            val message = packet.message
            if (message.isEmpty()) {
                return BrokenPacket(message, "empty message", null)
            }
            val rawEvent = when (val result = socketIoParser(StringSlice.of(message))) {
                is Ok<RawSocketIoFrame> -> result.value
                is Error -> {
                    return BrokenPacket(
                        message,
                        "Failed to parse event: ${result.message}, remaining: $result.rest",
                        t = null,
                    )
                }
            }

            return when (rawEvent.type) {
                0 -> decodeConnect(message, rawEvent, json)
                1 -> decodeDisconnect(rawEvent)
                2 -> decodeEvent(message, rawEvent, json)
                3 -> decodeAck(message, rawEvent, json)
                4 -> decodeConnectError(message, rawEvent, json)
                5 -> TODO("currently not needed")
                6 -> TODO("currently not needed")
                else -> BrokenPacket(
                    message,
                    "unknown packet type: ${rawEvent.type}",
                    t = null,
                )
            }
        }

        fun encode(json: Json, packet: SocketIoPacket): EngineIoPacket.TextMessage {

            return when (packet) {
                is Connect -> encodePacket(
                    json,
                    type = 0,
                    namespace = packet.namespace,
                    acknowledgeId = null,
                    data = null,
                )
                is Disconnect -> encodePacket(
                    json,
                    type = 1,
                    namespace = packet.namespace,
                    acknowledgeId = null,
                    data = null,
                )
                is Event -> encodePacket(
                    json,
                    type = 2,
                    namespace = packet.namespace,
                    acknowledgeId = packet.acknowledgeId,
                    data = JsonArray(listOf(JsonPrimitive(packet.name)) + packet.arguments),
                )
                is Acknowledge -> encodePacket(
                    json,
                    type = 3,
                    namespace = packet.namespace,
                    acknowledgeId = packet.acknowledgeId,
                    data = packet.data,
                )
                is ConnectError -> encodePacket(
                    json,
                    type = 4,
                    namespace = packet.namespace,
                    acknowledgeId = null,
                    data = json.encodeToJsonElement(packet.data),
                )
                is BinaryEvent -> TODO("currently not needed")
                is BinaryAcknowledge -> TODO("currently not needed")
                is BrokenPacket -> EngineIoPacket.TextMessage(packet.packet)
            }
        }
    }
}

private fun encodePacket(
    json: Json,
    type: Int,
    namespace: String?,
    acknowledgeId: Int?,
    data: JsonElement?,
): EngineIoPacket.TextMessage {
    // TODO binary support
    // val binaryAttachmentCountPart = binaryAttachmentCount?.let { "$it-" } ?: ""
    val binaryAttachmentCountPart = ""
    val namespacePart =
        if (namespace == null || namespace == "/") ""
        else "$namespace,"
    val acknowledgeIdPart = acknowledgeId?.toString() ?: ""
    val payload =
        if (data == null || data is JsonNull) ""
        else json.encodeToString(data)

    val message = "$type$binaryAttachmentCountPart$namespacePart$acknowledgeIdPart$payload"
    return EngineIoPacket.TextMessage(message)
}

data class RawSocketIoFrame(
    val namespace: String,
    val type: Int,
    val acknowledgmentId: Int?,
    val payload: String,
)

internal val jsonContainerOrStringFirstSet = setOf('[', '{', '"')
internal val typeParser = take { it.isDigit() }
    .map { it.toString().toInt() }
internal val numberOfBinaryAttachmentsParser = takeWhile(min = 1) { it.isDigit() }.andThenIgnore(take('-'))
    .map { it.toString().toInt() }
internal val namespaceParser = take('/').concat(takeWhile(min = 1) { it != ',' && it !in jsonContainerOrStringFirstSet }).andThenIgnore(take(','))
internal val acknowledgmentIdParser = takeWhile(min = 1) { it !in jsonContainerOrStringFirstSet }
    .map { it.toString().toInt() }
internal val payloadParser = takeWhile { true }
internal val socketIoParser = parseEntirely(typeParser.flatMap { type ->
    numberOfBinaryAttachmentsParser.optional().flatMap {
        namespaceParser.optional().flatMap { namespace ->
            acknowledgmentIdParser.optional().flatMap { acknowledgmentId ->
                payloadParser.map { payload ->
                    RawSocketIoFrame(
                        namespace?.toString() ?: "/",
                        type,
                        acknowledgmentId,
                        payload.toString(),
                    )
                }
            }
        }
    }
})

private fun decodeConnect(
    message: String,
    rawEvent: RawSocketIoFrame,
    json: Json,
): SocketIoPacket {
    val data = if (rawEvent.payload.isEmpty()) {
        null
    } else {
        val data = json.parseToJsonElement(rawEvent.payload)
        if (data !is JsonObject) {
            return SocketIoPacket.BrokenPacket(
                message,
                "Connect expects a JSON object or no payload at all!",
                t = null,
            )
        }
        data
    }

    return SocketIoPacket.Connect(rawEvent.namespace, data)
}

private fun decodeDisconnect(
    rawEvent: RawSocketIoFrame,
): SocketIoPacket {
    return SocketIoPacket.Disconnect(rawEvent.namespace)
}

private fun decodeEvent(
    message: String,
    rawEvent: RawSocketIoFrame,
    json: Json,
): SocketIoPacket {
    val data = json.parseToJsonElement(rawEvent.payload)
    if (data !is JsonArray) {
        return SocketIoPacket.BrokenPacket(
            message,
            "Events must have a JSON array as their payload!",
            t = null,
        )
    }

    val name = data.firstOrNull()

    return when {
        name == null -> {
            SocketIoPacket.BrokenPacket(message, "events needs at least the event name in the data", null)
        }
        name is JsonPrimitive && name.isString -> {
            SocketIoPacket.Event(rawEvent.namespace, rawEvent.acknowledgmentId, name.content, data.drop(1))
        }
        else -> {
            SocketIoPacket.BrokenPacket(message, "event name is not a string: $name", null)
        }
    }
}

private fun decodeAck(
    message: String,
    rawEvent: RawSocketIoFrame,
    json: Json,
): SocketIoPacket {
    // directly accessing the JSON as an array is safe here, since the regex makes sure this is an
    // array if it actually parses
    val data = if (rawEvent.payload.isEmpty()) {
        null
    } else {
        val data = json.parseToJsonElement(rawEvent.payload)
        if (data !is JsonArray) {
            return SocketIoPacket.BrokenPacket(
                message,
                "Acknowledgements must have a JSON array as data or no data at all!",
                t = null,
            )
        }
        data
    }

    if (rawEvent.acknowledgmentId == null) {
        return SocketIoPacket.BrokenPacket(
            message,
            "Acknowledgements must have an acknowledgmentId!",
            t = null,
        )
    }


    return SocketIoPacket.Acknowledge(rawEvent.namespace, rawEvent.acknowledgmentId, data)
}

private fun decodeConnectError(
    message: String,
    rawEvent: RawSocketIoFrame,
    json: Json,
): SocketIoPacket {
    val data = rawEvent.payload.ifEmpty { null }?.let(json::parseToJsonElement)

    return when {
        data == null -> {
            SocketIoPacket.ConnectError(rawEvent.namespace, null)
        }
        data is JsonObject -> {
            SocketIoPacket.ConnectError(rawEvent.namespace, json.decodeFromJsonElement(data))
        }
        data is JsonPrimitive && data.isString -> {
            SocketIoPacket.ConnectError(rawEvent.namespace, SocketIoPacket.ConnectError.Data(data.content, null))
        }
        else -> {
            SocketIoPacket.BrokenPacket(
                message,
                "error data is neither an object nor a string",
                t = null,
            )
        }
    }
}
