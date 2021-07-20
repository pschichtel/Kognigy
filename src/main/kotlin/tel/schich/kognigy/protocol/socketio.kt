package tel.schich.kognigy.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.ByteBuffer

sealed interface SocketIoPacket {

    val timestamp: Long

    sealed interface Namespaced {
        val namespace: String?
    }

    data class Connect(
        override val timestamp: Long,
        override val namespace: String?,
        val data: JsonObject?,
    ) : SocketIoPacket, Namespaced {
        companion object {
            val Format = """^(?:([^,]+),)?(\{.*})?$""".toRegex()
        }
    }

    data class Disconnect(
        override val timestamp: Long,
        override val namespace: String?,
    ) : SocketIoPacket, Namespaced {
        companion object {
            val Format = """^(?:([^,]+),)?""".toRegex()
        }
    }

    data class Event(
        override val timestamp: Long,
        override val namespace: String?,
        val acknowledgeId: Int?,
        val name: String,
        val arguments: List<JsonElement>,
    ) : SocketIoPacket, Namespaced {
        companion object {
            val Format = """^(?:([^,]+),)?(\d+)?(\[.*])$""".toRegex()
        }
    }

    data class Acknowledge(
        override val timestamp: Long,
        override val namespace: String?,
        val acknowledgeId: Int,
        val data: JsonArray?,
    ) : SocketIoPacket, Namespaced {
        companion object {
            val Format = """^(?:([^,]+),)?(\d+)(\[.*])?$""".toRegex()
        }
    }

    data class ConnectError(
        override val timestamp: Long,
        override val namespace: String?,
        val data: Data?,
    ) : SocketIoPacket, Namespaced {

        data class Data(val message: String, val data: JsonElement?)

        companion object {
            val Format = """^(?:([^,]+),)?(.+)?$""".toRegex()
        }
    }

    data class BinaryEvent(
        override val timestamp: Long,
        override val namespace: String?,
        val acknowledgeId: Int?,
        val name: String,
        val data: ByteBuffer,
    ) : SocketIoPacket, Namespaced {
        companion object {
            val Format = """^(?:([^,]+),)?(\d+)?(\[.*])?$""".toRegex()
        }
    }

    data class BinaryAcknowledge(
        override val timestamp: Long,
        override val namespace: String?,
        val acknowledgeId: Int,
        val data: ByteBuffer?,
    ) : SocketIoPacket, Namespaced {
        companion object {
            val Format = """^(?:([^,]+),)?(\d+)?(\[.*])?$""".toRegex()
        }
    }

    data class BrokenPacket(
        override val timestamp: Long,
        val packet: String,
        val reason: String,
        val t: Throwable?,
    ) : SocketIoPacket
}

fun decodeSocketIoPacket(json: Json, packet: EngineIoPacket.TextMessage): SocketIoPacket {
    val message = packet.message
    if (message.isEmpty()) {
        return SocketIoPacket.BrokenPacket(packet.timestamp, message, "empty message", null)
    }

    fun matchFormat(format: Regex, block: (MatchResult.Destructured) -> SocketIoPacket): SocketIoPacket {
        val match = format.matchEntire(message.substring(1))
        return if (match == null) SocketIoPacket.BrokenPacket(
            packet.timestamp,
            message,
            "did not match packet format",
            null,
        )
        else try {
            block(match.destructured)
        } catch (e: SerializationException) {
            SocketIoPacket.BrokenPacket(packet.timestamp, message, "data failed to parse as JSON", e)
        }
    }

    return when (val type = message[0]) {
        '0' -> matchFormat(SocketIoPacket.Connect.Format) { (namespaceStr, dataStr) ->
            val namespace = namespaceStr.ifEmpty { null }
            // directly accessing the JSON as an object is safe here, since the regex makes sure this is an object if it actually parses
            val data = if (dataStr.isEmpty()) null else json.parseToJsonElement(dataStr).jsonObject

            SocketIoPacket.Connect(packet.timestamp, namespace, data)
        }
        '1' -> matchFormat(SocketIoPacket.Disconnect.Format) { (namespaceStr) ->
            val namespace = namespaceStr.ifEmpty { null }

            SocketIoPacket.Disconnect(packet.timestamp, namespace)
        }
        '2' -> matchFormat(SocketIoPacket.Event.Format) { (namespaceStr, acknowledgeIdStr, dataStr) ->
            val namespace = namespaceStr.ifEmpty { null }
            val acknowledgeId = acknowledgeIdStr.ifEmpty { null }?.toInt()
            // directly accessing the JSON as an array is safe here, since the regex makes sure this is an array if it actually parses
            val data = json.parseToJsonElement(dataStr).jsonArray
            val name = data.firstOrNull()

            when {
                name == null -> SocketIoPacket.BrokenPacket(
                    packet.timestamp,
                    message,
                    "events needs at least the event name in the data",
                    null,
                )
                name is JsonPrimitive && name.isString -> SocketIoPacket.Event(
                    packet.timestamp,
                    namespace,
                    acknowledgeId,
                    name.content,
                    data.drop(1),
                )
                else -> SocketIoPacket.BrokenPacket(
                    packet.timestamp,
                    message,
                    "event name is not a string: $name",
                    null,
                )
            }
        }
        '3' -> matchFormat(SocketIoPacket.Acknowledge.Format) { (namespaceStr, acknowledgeIdStr, dataStr) ->
            val namespace = namespaceStr.ifEmpty { null }
            val acknowledgeId = acknowledgeIdStr.toInt()
            // directly accessing the JSON as an array is safe here, since the regex makes sure this is an array if it actually parses
            val data = dataStr.ifEmpty { null }?.let { json.parseToJsonElement(it).jsonArray }

            SocketIoPacket.Acknowledge(packet.timestamp, namespace, acknowledgeId, data)
        }
        '4' -> matchFormat(SocketIoPacket.ConnectError.Format) { (namespaceStr, dataStr) ->
            val namespace = namespaceStr.ifEmpty { null }
            val data = dataStr.ifEmpty { null }?.let(json::parseToJsonElement)

            when {
                data == null -> SocketIoPacket.ConnectError(packet.timestamp, namespace, null)
                data is JsonObject -> SocketIoPacket.ConnectError(
                    packet.timestamp,
                    namespace,
                    json.decodeFromJsonElement(data),
                )
                data is JsonPrimitive && data.isString -> SocketIoPacket.ConnectError(
                    packet.timestamp,
                    namespace,
                    SocketIoPacket.ConnectError.Data(data.content, null),
                )
                else -> SocketIoPacket.BrokenPacket(
                    packet.timestamp,
                    message,
                    "error data is neither an object nor a string",
                    null,
                )
            }
        }
        '5' -> matchFormat(SocketIoPacket.BinaryEvent.Format) {
            TODO()
        }
        '6' -> matchFormat(SocketIoPacket.BinaryAcknowledge.Format) {
            TODO()
        }
        else -> SocketIoPacket.BrokenPacket(packet.timestamp, message, "unknown packet type: $type", null)
    }
}

private fun encodePacket(
    json: Json,
    type: Int,
    timestamp: Long,
    binaryAttachmentCount: Int?,
    namespace: String?,
    acknowledgeId: Int?,
    data: JsonElement?,
): EngineIoPacket.TextMessage {
    val binaryAttachmentCountPart = binaryAttachmentCount?.let { "$it-" } ?: ""
    val namespacePart =
        if (namespace == null || namespace == "/") ""
        else "$namespace,"
    val acknowledgeIdPart = acknowledgeId?.toString() ?: ""
    val payload =
        if (data == null || data is JsonNull) ""
        else json.encodeToString(data)

    return EngineIoPacket.TextMessage(
        timestamp,
        "$type$binaryAttachmentCountPart$namespacePart$acknowledgeIdPart$payload",
    )
}

fun encodeSocketIoPacket(json: Json, packet: SocketIoPacket): EngineIoPacket.TextMessage = when (packet) {
    is SocketIoPacket.Connect -> encodePacket(
        json,
        0,
        packet.timestamp,
        null,
        packet.namespace,
        null,
        null,
    )
    is SocketIoPacket.Disconnect -> encodePacket(
        json,
        1,
        packet.timestamp,
        null,
        packet.namespace,
        null,
        null,
    )
    is SocketIoPacket.Event -> encodePacket(
        json,
        2,
        packet.timestamp,
        null,
        packet.namespace,
        packet.acknowledgeId,
        JsonArray(listOf(JsonPrimitive(packet.name)) + packet.arguments),
    )
    is SocketIoPacket.Acknowledge -> encodePacket(
        json,
        3,
        packet.timestamp,
        null,
        packet.namespace,
        packet.acknowledgeId,
        packet.data,
    )
    is SocketIoPacket.ConnectError -> encodePacket(
        json,
        4,
        packet.timestamp,
        null,
        packet.namespace,
        null,
        json.encodeToJsonElement(packet.data),
    )
    is SocketIoPacket.BinaryEvent -> TODO()
    is SocketIoPacket.BinaryAcknowledge -> TODO()
    is SocketIoPacket.BrokenPacket -> EngineIoPacket.TextMessage(packet.timestamp, packet.packet)
}
