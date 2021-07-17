package tel.schich.kognigy

import io.ktor.client.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.Json
import tel.schich.kognigy.wire.*
import java.net.URI

class Kognigy(
    private val uri: URI,
    private val client: HttpClient,
    private val json: Json,
    private val coroutineScope: CoroutineScope,
) {

    init {
        if (!(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) {
            throw IllegalArgumentException("Protocol must be http or https")
        }
    }

    suspend fun connect(): Pair<SendChannel<CognigyEvent>, ReceiveChannel<CognigyEvent>> {

        val input = Channel<CognigyEvent>(Channel.UNLIMITED)
        val output = Channel<CognigyEvent>(Channel.UNLIMITED)

        val scheme =
            if (uri.scheme.equals("http", false)) "ws"
            else "wss"

        val wsUri = URI(scheme, uri.userInfo, uri.host, uri.port, "/socket.io/", "transport=websocket", null).toString()

        coroutineScope.launch {
            try {
                client.ws(urlString = wsUri) {
                    handle(this, input, output)
                }
            } catch (e: Exception) {
                e.printStack()
            }
        }

        client.close()

        return Pair(input, output)
    }

    private suspend fun handle(session: DefaultClientWebSocketSession, input: ReceiveChannel<CognigyEvent>, output: SendChannel<CognigyEvent>) {
        session.launch {
            sendCognigyEvents(input, session::send)
        }

        receiveCognigyEvents(session.incoming, output)
    }

    private suspend fun sendCognigyEvents(input: ReceiveChannel<CognigyEvent>, sink: suspend (Frame) -> Unit) {
        for (event in input) {
            val socketIoFrame = encodeCognigyFrame(json, CognigyFrame.Event(event))
            sink(Frame.Text(encodeSocketIoFrame(json, SocketIoFrame.Data(socketIoFrame))))
        }
    }

    private suspend fun receiveCognigyEvents(incoming: ReceiveChannel<Frame>, output: SendChannel<CognigyEvent>) {
        for (frame in incoming) {
            when (frame) {
                is Frame.Text -> {
                    when (val socketIoFrame = parseFrame(json, frame.readText())) {
                        is SocketIoFrame.Open -> println("socket.io open: $socketIoFrame")
                        is SocketIoFrame.Close -> println("socket.io close")
                        is SocketIoFrame.Data -> {
                            when (val cognigyFrame = parseCognigyFrame(json, socketIoFrame.data)) {
                                is CognigyFrame.Noop -> println("cognigy noop")
                                is CognigyFrame.Event -> output.send(cognigyFrame.event)
                                null -> {
                                }
                            }

                        }
                        is SocketIoFrame.Ping -> println("socket.io ping")
                        is SocketIoFrame.Pong -> println("socket.io pong")
                        is SocketIoFrame.Upgrade -> println("socket.io upgrade")
                        is SocketIoFrame.Noop -> println("socket.io noop")
                        null -> {
                        }
                    }
                }
                is Frame.Binary -> {
                    println("Unable to process binary data!")
                }
                is Frame.Close,
                is Frame.Ping,
                is Frame.Pong -> {
                }
            }
        }
    }
}