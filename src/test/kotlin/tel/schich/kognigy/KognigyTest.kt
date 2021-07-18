package tel.schich.kognigy

import io.ktor.client.*
import io.ktor.client.features.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.test.Test
import kotlinx.serialization.json.*
import mu.KLoggable
import tel.schich.kognigy.protocol.*
import java.net.URI
import kotlin.random.Random

class KognigyTest {

    @Test
    fun test() {
        runBlocking {
            val client = HttpClient {
                install(WebSockets)
            }

            val json = Json { encodeDefaults = true }
            val token = System.getenv("ENDPOINT_TOKEN")
            val uri = URI(System.getenv("ENDPOINT_URL"))

            val kognigy = Kognigy(client, json, CoroutineScope(Dispatchers.Default))

            val session = kognigy.connect(uri, token, "session!", "user!", "channel!", "kognigy!")
            session.sendInput("Start!")

            session.output
                .filterNot { it is CognigyEvent.FinalPing }
                .onEach { event ->
                    logger.info("$event")
                    delay(5000)
                    session.sendInput("Some text! ${Random.nextInt()}")
                }
                .launchIn(this)
        }
    }

    private companion object : KLoggable {
        override val logger by lazy { logger() }
    }

}