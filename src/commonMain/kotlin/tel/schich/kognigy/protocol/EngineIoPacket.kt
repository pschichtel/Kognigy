package tel.schich.kognigy.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tel.schich.kognigy.Frame

class PongTimeoutException(message: String) : CancellationException(message)
class PingTimeoutException(message: String) : CancellationException(message)

class Data(val data: ByteArray) {
    fun toHexString() = data.joinToString(" ") { byte ->
        byte.toUByte().toString(radix = 16).uppercase().padStart(length = 2, padChar = '0')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        val otherData = (other as? Data)?.data ?: return false

        return data.contentEquals(otherData)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}

/**
 * Based on: [github.com/socketio/engine.io-protocol](https://github.com/socketio/engine.io-protocol)
 */
sealed interface EngineIoPacket {
    @Serializable
    data class Open(
        @SerialName("sid")
        val sessionId: String,
        val upgrades: List<String>,
        @SerialName("pingInterval")
        val pingIntervalMillis: Long,
        @SerialName("pingTimeout")
        val pingTimeoutMillis: Long,
        @SerialName("maxPayload")
        val maxPayloadBytes: Long,
    ) : EngineIoPacket

    object Close : EngineIoPacket

    object Ping : EngineIoPacket

    object Pong : EngineIoPacket

    data class TextMessage(
        val message: String,
    ) : EngineIoPacket

    data class BinaryMessage(
        val data: Data,
    ) : EngineIoPacket

    object Upgrade : EngineIoPacket

    object Noop : EngineIoPacket

    data class Error(
        val data: String,
        val reason: String,
        val t: Throwable?,
    ) : EngineIoPacket

    companion object {
        fun decode(json: Json, frame: Frame.Text): EngineIoPacket {
            val message = frame.text
            if (message.isEmpty()) {
                return Error(message, "empty message", null)
            }
            return when (val type = message[0]) {
                '0' -> {
                    try {
                        json.decodeFromString<Open>(message.substring(1))
                    } catch (e: SerializationException) {
                        Error(message, "broken json in open packet", e)
                    }
                }
                '1' -> Close
                '2' -> Ping
                '3' -> Pong
                '4' -> TextMessage(message.substring(1))
                '5' -> Upgrade
                '6' -> Noop
                else -> Error(message, "unknown packet type: $type", null)
            }
        }

        fun encode(json: Json, packet: EngineIoPacket): Frame = when (packet) {
            is Open -> Frame.Text("0${json.encodeToString(packet)}")
            is Close -> Frame.Text("1")
            is Ping -> Frame.Text("2probe")
            is Pong -> Frame.Text("3probe")
            is TextMessage -> Frame.Text("4${packet.message}")
            is BinaryMessage -> Frame.Binary(packet.data.data)
            is Upgrade -> Frame.Text("5")
            is Noop -> Frame.Text("6")
            is Error -> Frame.Text(packet.data)
        }
    }
}
