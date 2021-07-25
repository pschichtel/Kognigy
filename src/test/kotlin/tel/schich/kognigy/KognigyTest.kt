package tel.schich.kognigy

import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlin.test.Test
import mu.KLoggable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import tel.schich.kognigy.protocol.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals

class KognigyTest {

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun test() {
        URLBuilder()
        runBlocking {
            val uri = Url(System.getenv(ENDPOINT_URL_ENV))
            val token = System.getenv(ENDPOINT_TOKEN_ENV)

            val kognigy = Kognigy.simple()

            val session = kognigy.connect(uri, token, "session!", "user!", "channel!", "kognigy!")
            session.sendInput("Start!")

            val counter = AtomicInteger(0)
            session.output
                .filterNot { it is CognigyEvent.FinalPing }
                .take(5)
                .onEach { event ->
                    logger.info("$event")
                    if (counter.incrementAndGet() < 5) {
                        delay(2500)
                        session.sendInput("Some text! ${Random.nextInt()}")
                    }
                }
                .launchIn(this)
                .join()
            session.close()

            assertEquals(5, counter.get(), "should take exactly 5 events")
        }
    }

    private companion object : KLoggable {
        override val logger = logger()

        const val ENDPOINT_URL_ENV = "ENDPOINT_URL"
        const val ENDPOINT_TOKEN_ENV = "ENDPOINT_TOKEN"
    }

}