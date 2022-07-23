package tel.schich.kognigy.protocol

import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Based on: [github.com/socketio/engine.io-protocol](https://github.com/socketio/engine.io-protocol)
 */
sealed interface EngineIoPacket {
    @Serializable
    data class Open(
        val sid: String,
        val upgrades: List<String>,
        val pingInterval: Int,
        val pingTimeout: Int,
    ) : EngineIoPacket

    object Close : EngineIoPacket

    object Ping : EngineIoPacket

    object Pong : EngineIoPacket

    data class TextMessage(
        val message: String,
    ) : EngineIoPacket

    class BinaryMessage(
        val data: ByteArray,
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
            val message = frame.readText()
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
            is BinaryMessage -> Frame.Binary(true, packet.data)
            is Upgrade -> Frame.Text("5")
            is Noop -> Frame.Text("6")
            is Error -> Frame.Text(packet.data)
        }
    }
}
