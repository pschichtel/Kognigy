package tel.schich.kognigy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.khronos.webgl.Uint8Array
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import kotlin.coroutines.coroutineContext

class JsConnection(
    private val job: CompletableJob,
    override val input: SendChannel<Frame>,
    override val output: ReceiveChannel<Frame>,
) : Connection {
    override suspend fun disconnect() {
        input.close()
        output.cancel()
        job.complete()
    }
}

actual suspend fun connectWebsocket(uri: String, bufferSize: Int): Connection {
    val ws = WebSocket(uri)
    val job = Job(coroutineContext[Job]!!)
    val coroutineScope = CoroutineScope(job)
    val complete = CompletableDeferred<Connection>()
    val input = Channel<Frame>()
    val output = Channel<Frame>()
    ws.onopen = {
        complete.complete(JsConnection(job, input, output))
    }
    ws.onmessage = { event: MessageEvent ->
        TODO("no clue how to properly handle this")
    }
    ws.onerror = { event ->
        input.close()
        output.cancel()
        job.complete()
    }

    val receiveJob = coroutineScope.launch {
        input.consumeEach { frame ->
            when (frame) {
                is Frame.Binary -> ws.send(Uint8Array(frame.data.toTypedArray()))
                is Frame.Close -> {
                    ws.close(frame.statusCode.toShort(), frame.reason.orEmpty())
                    job.complete()
                    return@launch
                }
                is Frame.Ping -> {}
                is Frame.Pong -> {}
                is Frame.Text -> ws.send(frame.text)
            }
        }
    }

    receiveJob.invokeOnCompletion {
        ws.close()
        input.close()
        output.cancel()
    }

    return complete.await()
}