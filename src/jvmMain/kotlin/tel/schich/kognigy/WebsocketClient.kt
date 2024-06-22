package tel.schich.kognigy

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import kotlin.coroutines.coroutineContext

private class WebsocketListner(
    private val job: CompletableJob,
    private val coroutineScope: CoroutineScope,
    private val output: Channel<Frame>,
    private val input: Channel<Frame>,
) : WebSocket.Listener {
    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean) = coroutineScope.future {
        output.send(Frame.Text(data.toString(), last))
        webSocket.request(1)
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        return if (hasArray()) {
            array()
        } else {
            ByteArray(remaining()).also {
                get(it)
            }
        }
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean) = coroutineScope.future {
        output.send(Frame.Binary(data.toByteArray(), last))
        webSocket.request(1)
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*> = coroutineScope.future {
        output.send(Frame.Ping(message.toByteArray()))
        webSocket.request(1)
    }

    override fun onPong(webSocket: WebSocket, message: ByteBuffer) = coroutineScope.future {
        output.send(Frame.Pong(message.toByteArray()))
        webSocket.request(1)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?) = coroutineScope.future {
        output.send(Frame.Close(statusCode, reason))
        job.complete()
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        input.close(error)
        output.cancel()
        job.complete()
    }
}

class JavaConnection(override val input: SendChannel<Frame>, override val output: ReceiveChannel<Frame>, private val job: CompletableJob) : Connection {
    override suspend fun disconnect() {
        input.close()
        output.cancel()
        job.complete()
    }
}

actual suspend fun connectWebsocket(uri: String, bufferSize: Int): Connection {
    val client = HttpClient.newHttpClient()
    val output = Channel<Frame>()
    val input = Channel<Frame>()

    val job = Job(coroutineContext[Job]!!)
    val coroutineScope = CoroutineScope(job)

    val listener = WebsocketListner(job, coroutineScope, output, input)
    val ws = client.newWebSocketBuilder()
        .buildAsync(URI(uri), listener)
        .await()

    val receiveJob = coroutineScope.launch {
        input.consumeEach { frame ->
            if (ws.isOutputClosed) {
                input.close()
                output.cancel()
            }
            when (frame) {
                is Frame.Binary -> ws.sendBinary(ByteBuffer.wrap(frame.data), frame.last)
                is Frame.Close -> {
                    ws.sendClose(frame.statusCode, frame.reason)
                    job.complete()
                    return@launch
                }
                is Frame.Ping -> ws.sendPing(ByteBuffer.wrap(frame.message))
                is Frame.Pong -> ws.sendPong(ByteBuffer.wrap(frame.message))
                is Frame.Text -> ws.sendText(frame.text, frame.last)
            }
        }
    }

    receiveJob.invokeOnCompletion {
        ws.abort()
        input.close()
        output.cancel()
    }

    return JavaConnection(input, output, job)
}