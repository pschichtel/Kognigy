package tel.schich.kognigy

import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlin.test.Test
import mu.KLoggable
import tel.schich.kognigy.protocol.*
import kotlin.random.Random
import kotlin.test.Ignore

class KognigyTest {

    @Ignore
    @Test
    fun test() {
        URLBuilder()
        runBlocking {
            val token = System.getenv("ENDPOINT_TOKEN")
            val uri = Url(System.getenv("ENDPOINT_URL"))

            val kognigy = Kognigy.simple()

            val session = kognigy.connect(uri, token, "session!", "user!", "channel!", "kognigy!")
            session.sendInput("Start!")

            session.output
                .filterNot { (event) -> event !is CognigyEvent.Output }
                .take(5)
                .onEach { (event, timestamp) ->
                    logger.info("$timestamp -> $event")
                    delay(5000)
                    session.sendInput("Some text! ${Random.nextInt()}")
                }
                .launchIn(this)
                .join()

            session.close()
        }
    }

    private companion object : KLoggable {
        override val logger by lazy { logger() }
    }

}