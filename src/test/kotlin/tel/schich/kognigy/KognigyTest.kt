package tel.schich.kognigy

import io.ktor.client.*
import io.ktor.client.features.websocket.*
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlinx.serialization.json.*
import tel.schich.kognigy.wire.*
import java.net.URI
import kotlin.random.Random

class KognigyTest {

    //@Test
    fun test() {
        runBlocking {
            val client = HttpClient {
                install(WebSockets)
            }

            val json = Json { encodeDefaults = true }
            val token = "..."
            val uri = URI("https://endpoint-trial.cognigy.ai/$token")

            val kognigy = Kognigy(uri, client, json, CoroutineScope(Dispatchers.Default))

            val (input, output) = kognigy.connect()
            input.send(someInput(token, "Start!"))

            launch {
                for (event in output) {
                    println(event)
                    delay(5000)
                    val inputFrame = someInput(token, "Some text! ${Random.nextInt()}")
                    input.send(inputFrame)
                }
            }
        }
    }

    private fun someInput(token: String, text: String): CognigyEvent.ProcessInput {
        return CognigyEvent.ProcessInput(
            URLToken = token,
            userId = "user!",
            sessionId = "session!",
            channel = "kognigy",
            source = "",
            passthroughIP = null,
            reloadFlow = false,
            resetFlow = false,
            resetState = false,
            resetContext = false,
            text = text,
            data = null,
        )
    }

}