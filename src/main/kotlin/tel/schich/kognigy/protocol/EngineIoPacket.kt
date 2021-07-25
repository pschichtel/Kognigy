package tel.schich.kognigy.protocol

import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

sealed interface EngineIoPacket {
    @Serializable
    data class Open(val sid: String, val upgrades: List<String>, val pingInterval: Int, val pingTimeout: Int) : EngineIoPacket
    object Close : EngineIoPacket
    object Ping : EngineIoPacket
    object Pong : EngineIoPacket
    data class TextMessage(val message: String) : EngineIoPacket
    data class BinaryMessage(val data: ByteBuffer) : EngineIoPacket
    object Upgrade : EngineIoPacket
    object Noop : EngineIoPacket
    data class Error(val data: String, val reason: String, val t: Throwable?) : EngineIoPacket

    companion object {
        fun decode(json: Json, frame: Frame.Text): EngineIoPacket {
            val message = frame.readText()
            if (message.isEmpty()) {
                return EngineIoPacket.Error(message, "empty message", null)
            }
            return when (val type = message[0]) {
                '0' -> {
                    try {
                        json.decodeFromString<EngineIoPacket.Open>(message.substring(1))
                    } catch (e: SerializationException) {
                        EngineIoPacket.Error(message, "broken json in open packet", e)
                    }
                }
                '1' -> EngineIoPacket.Close
                '2' -> EngineIoPacket.Ping
                '3' -> EngineIoPacket.Pong
                '4' -> EngineIoPacket.TextMessage(message.substring(1))
                '5' -> EngineIoPacket.Upgrade
                '6' -> EngineIoPacket.Noop
                else -> EngineIoPacket.Error(message, "unknown packet type: $type", null)
            }
        }

        fun encode(json: Json, packet: EngineIoPacket): Frame = when (packet) {
            is EngineIoPacket.Open -> Frame.Text("0${json.encodeToString(packet)}")
            is EngineIoPacket.Close -> Frame.Text("1")
            is EngineIoPacket.Ping -> Frame.Text("2probe")
            is EngineIoPacket.Pong -> Frame.Text("3probe")
            is EngineIoPacket.TextMessage -> Frame.Text("4${packet.message}")
            is EngineIoPacket.BinaryMessage -> Frame.Binary(true, packet.data)
            is EngineIoPacket.Upgrade -> Frame.Text("5")
            is EngineIoPacket.Noop -> Frame.Text("6")
            is EngineIoPacket.Error -> Frame.Text(packet.data)
        }
    }
}
