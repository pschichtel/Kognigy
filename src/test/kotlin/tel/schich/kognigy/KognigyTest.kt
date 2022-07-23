package tel.schich.kognigy

import io.ktor.client.engine.cio.*
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import tel.schich.kognigy.protocol.CognigyEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private val logger = KotlinLogging.logger {}

class KognigyTest {

    /**
     * The endpoint that is used here should be backed by a flow that simply reproduces a
     * response for each input it gets.
     */
    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun cognigyConnectivity() {
        runBlocking {
            val uri = Url(System.getenv(ENDPOINT_URL_ENV))
            val token = System.getenv(ENDPOINT_TOKEN_ENV)

            val kognigy = Kognigy.simple(CIO, pingIntervalMillis = 700)

            val session = KognigySession("session!", uri, token, "user!", "channel!", "kognigy!")

            val connection = kognigy.connect(session)
            connection.sendInput("Start!")

            val counter = AtomicInteger(0)
            connection.output
                .consumeAsFlow()
                .filterNot { it is CognigyEvent.FinalPing }
                .take(5)
                .onEach { event ->
                    logger.info("$event")
                    if (counter.incrementAndGet() < 5) {
                        delay(5000)
                        connection.sendInput("Some text! ${Random.nextInt()}")
                    }
                }
                .onCompletion { t ->
                    when (t) {
                        null -> logger.info { "flow completed normally" }
                        is CancellationException -> logger.info(t) { "flow got cancelled" }
                        else -> logger.error(t) { "flow completed abnormally" }
                    }
                }
                .launchIn(this)
                .join()

            connection.close()

            assertEquals(5, counter.get(), "should take exactly 5 events")
        }
    }

    private companion object {
        const val ENDPOINT_URL_ENV = "ENDPOINT_URL"
        const val ENDPOINT_TOKEN_ENV = "ENDPOINT_TOKEN"
    }
}
