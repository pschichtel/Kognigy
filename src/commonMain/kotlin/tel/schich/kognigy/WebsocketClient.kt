package tel.schich.kognigy

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

sealed interface Frame {
    data class Text(val text: String, val last: Boolean = true) : Frame
    data class Binary(val data: ByteArray, val last: Boolean = true) : Frame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Binary

            if (!data.contentEquals(other.data)) return false
            if (last != other.last) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + last.hashCode()
            return result
        }
    }

    data class Close(val statusCode: Int, val reason: String?) : Frame
    data class Ping(val message: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Ping

            return message.contentEquals(other.message)
        }

        override fun hashCode(): Int {
            return message.contentHashCode()
        }
    }

    data class Pong(val message: ByteArray) : Frame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Pong

            return message.contentEquals(other.message)
        }

        override fun hashCode(): Int {
            return message.contentHashCode()
        }
    }
}

interface Connection {
    val output: ReceiveChannel<Frame>
    val input: SendChannel<Frame>

    suspend fun disconnect()
}

expect suspend fun connectWebsocket(uri: String, bufferSize: Int): Connection