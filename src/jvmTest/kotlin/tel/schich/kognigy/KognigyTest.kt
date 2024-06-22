package tel.schich.kognigy

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.config
import io.ktor.client.engine.http
import io.ktor.client.engine.java.Java
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import mu.KotlinLogging
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import tel.schich.kognigy.protocol.ChannelName
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EndpointToken
import tel.schich.kognigy.protocol.SessionId
import tel.schich.kognigy.protocol.UserId
import java.time.Duration
import java.time.Instant
import java.util.UUID.randomUUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private val logger = KotlinLogging.logger {}

data class TestIteration(val i: Int) : AbstractCoroutineContextElement(TestIteration) {
    companion object Key : CoroutineContext.Key<CoroutineName>
}

class KognigyTest {

    private fun defaultKognigy(
        engine: HttpClientEngineFactory<*> = Java,
        endpointReadyTimeoutOfShame: Long = -1,
    ): Kognigy {
        val proxy = System.getenv("COGNIGY_HTTP_PROXY")?.ifBlank { null }?.let { ProxyBuilder.http(it) }

        val configuredEngine = engine.config {
            this.proxy = proxy
        }
        return Kognigy(configuredEngine, endpointReadyTimeoutOfShameMillis = endpointReadyTimeoutOfShame)
    }

    private suspend fun runTest(
        kognigy: Kognigy = defaultKognigy(),
        sessionId: SessionId? = null,
        userId: UserId? = null,
        delay: Long = 3000,
        iterations: Int = 5,
        newPayload: () -> Pair<String, JsonElement?>,
    ) {
        val uri = Url(System.getenv(ENDPOINT_URL_ENV))
        val token = System.getenv(ENDPOINT_TOKEN_ENV)

        val randomId = randomUUID().toString()

        val session = KognigySession(
            id = sessionId ?: SessionId(randomId),
            uri,
            EndpointToken(token),
            userId = userId ?: UserId("kognigy-integration-test-${randomId}"),
            ChannelName("kognigy"),
        )

//        logger.info { "Session: $session" }

        val connection = kognigy.connect(session)

        suspend fun sendInput(input: Pair<String, JsonElement?>) {
//            logger.info { "Input: $input" }
            connection.sendInput(input.first, input.second)
        }

        var totalMillis = 0L
        var maxMillis = Long.MIN_VALUE
        var minMillis = Long.MAX_VALUE

        repeat(iterations) {
            val input = newPayload()
            sendInput(input)
            val start = Instant.now()
            while (true) {
                val event = try {
                    withTimeout(20000) {
                        connection.output.receive().also {
                            val duration = Duration.between(start, Instant.now()).toMillis()
                            totalMillis += duration
                            if (duration > maxMillis) {
                                maxMillis = duration
                            }
                            if (duration < minMillis) {
                                minMillis = duration
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    fail("Receive timed out!", e)
                }
//                logger.info { "Received Event: $event" }
                if (event !is CognigyEvent.Output.Message) {
                    continue
                }

                assertEquals("you said \"${input.first}\"\ndata was \"{}\"", event.data.text)

                delay(delay)

                break
            }
        }

        connection.close()

        val avg = totalMillis / iterations
        logger.info { "run complete - min=$minMillis max=$maxMillis avg=$avg" }
    }

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
            runTest { "Some text! ${randomUUID()}" to null }
        }
    }

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun loadTest() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        val activeRuns = AtomicInteger(0)
        var i = 0

        val kognigy = defaultKognigy()

        coroutineScope {
            withContext(dispatcher) {
                val coroutineName = CoroutineName("kognigy-test-run")
                while (true) {
                    val iteration = i++
                    launch(Job() + Dispatchers.IO + coroutineName + TestIteration(iteration)) {
                        activeRuns.incrementAndGet()
                        try {
                            runTest(kognigy, iterations = 1) { "Some text from $iteration! ${randomUUID()}" to null }
                        } finally {
                            activeRuns.decrementAndGet()
                        }
                    }
                    if (iteration % 10 == 0) {
                        logger.info("######## $iteration. iteration, $activeRuns active runs")
                    }
                    delay(timeMillis = 50)
                }
            }
        }
    }

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun missingSessionId() {
        runBlocking {
            assertThrows<EarlyDisconnectException> {
                runTest(sessionId = SessionId("")) {
                    "Some text! ${randomUUID()}" to null
                }
            }
        }
    }

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun missingUserId() {
        runBlocking {
            assertThrows<EarlyDisconnectException> {
                runTest(userId = UserId("")) {
                    "Some text! ${randomUUID()}" to null
                }
            }
        }
    }

    private companion object {
        const val ENDPOINT_URL_ENV = "ENDPOINT_URL"
        const val ENDPOINT_TOKEN_ENV = "ENDPOINT_TOKEN"
    }
}
