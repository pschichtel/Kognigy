package tel.schich.kognigy.wire

import io.ktor.http.cio.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

sealed interface SocketIoPacket {
    @Serializable
    data class Open(val sid: String, val upgrades: List<String>, val pingInterval: Int, val pingTimeout: Int) : SocketIoPacket
    object Close : SocketIoPacket
    object Ping : SocketIoPacket
    object Pong : SocketIoPacket
    data class TextMessage(val data: String) : SocketIoPacket
    data class BinaryMessage(val data: ByteBuffer) : SocketIoPacket
    object Upgrade : SocketIoPacket
    object Noop : SocketIoPacket
    data class Error(val data: String, val reason: String, val t: Throwable?) : SocketIoPacket
}

fun parseSocketIoPacketFromText(json: Json, message: String): SocketIoPacket {
    if (message.isEmpty()) {
        return SocketIoPacket.Error(message, "empty message", null)
    }
    return when (val type = message[0]) {
        '0' -> {
            try {
                json.decodeFromString<SocketIoPacket.Open>(message.substring(1))
            } catch (e: SerializationException) {
                SocketIoPacket.Error(message, "broken json in open packet", e)
            }
        }
        '1' -> SocketIoPacket.Close
        '2' -> SocketIoPacket.Ping
        '3' -> SocketIoPacket.Pong
        '4' -> SocketIoPacket.TextMessage(message.substring(1))
        '5' -> SocketIoPacket.Upgrade
        '6' -> SocketIoPacket.Noop
        else -> SocketIoPacket.Error(message, "unknown packet type: $type", null)
    }
}

fun encodeSocketIoPacket(json: Json, packet: SocketIoPacket): Frame = when (packet) {
    is SocketIoPacket.Open -> Frame.Text("0${json.encodeToString(packet)}")
    is SocketIoPacket.Close -> Frame.Text("1")
    is SocketIoPacket.Ping -> Frame.Text("2probe")
    is SocketIoPacket.Pong -> Frame.Text("3probe")
    is SocketIoPacket.TextMessage -> Frame.Text("4${packet.data}")
    is SocketIoPacket.BinaryMessage -> Frame.Binary(true, packet.data)
    is SocketIoPacket.Upgrade -> Frame.Text("5")
    is SocketIoPacket.Noop -> Frame.Text("6")
    is SocketIoPacket.Error -> Frame.Text(packet.data)
}