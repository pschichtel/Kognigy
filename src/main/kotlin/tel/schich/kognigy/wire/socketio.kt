package tel.schich.kognigy.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface SocketIoFrame {
    @Serializable
    data class Open(val sid: String, val upgrades: List<String>, val pingInterval: Int, val pingTimeout: Int) : SocketIoFrame
    object Close : SocketIoFrame
    object Ping : SocketIoFrame
    object Pong : SocketIoFrame
    data class Data(val data: String) : SocketIoFrame
    object Upgrade : SocketIoFrame
    object Noop : SocketIoFrame
}

fun parseFrame(json: Json, message: String): SocketIoFrame? = when (message[0]) {
    '0' -> json.decodeFromString<SocketIoFrame.Open>(message.substring(1))
    '1' -> SocketIoFrame.Close
    '2' -> SocketIoFrame.Ping
    '3' -> SocketIoFrame.Pong
    '4' -> SocketIoFrame.Data(message.substring(1))
    '5' -> SocketIoFrame.Upgrade
    '6' -> SocketIoFrame.Noop
    else -> null
}

fun encodeSocketIoFrame(json: Json, frame: SocketIoFrame): String = when (frame) {
    is SocketIoFrame.Open -> "0${json.encodeToString(frame)}"
    is SocketIoFrame.Close -> "1"
    is SocketIoFrame.Ping -> "2probe"
    is SocketIoFrame.Pong -> "3probe"
    is SocketIoFrame.Data -> "4${frame.data}"
    is SocketIoFrame.Upgrade -> "5"
    is SocketIoFrame.Noop -> "6"
}