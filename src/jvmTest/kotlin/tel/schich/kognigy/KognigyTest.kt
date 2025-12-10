package tel.schich.kognigy

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.config
import io.ktor.client.engine.http
import io.ktor.client.engine.java.Java
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.JavaHttpEngine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import tel.schich.kognigy.ConnectionSuccessReason.ASSUMED_READY_WITHOUT_TIMEOUT
import tel.schich.kognigy.ConnectionSuccessReason.ASSUMED_READY_WITH_TIMEOUT
import tel.schich.kognigy.ConnectionSuccessReason.ENDPOINT_READY_SIGNAL
import tel.schich.kognigy.protocol.ChannelName
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EndpointToken
import tel.schich.kognigy.protocol.SessionId
import tel.schich.kognigy.protocol.UserId
import java.util.UUID.randomUUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

data class TestIteration(val i: Int) : AbstractCoroutineContextElement(TestIteration) {
    companion object Key : CoroutineContext.Key<CoroutineName>
}

class KognigyTest {

    private fun defaultKognigy(
        engine: HttpClientEngineFactory<*> = Java,
        endpointReadyTimeout: Duration = Duration.INFINITE,
    ): Kognigy {
        val proxy = System.getenv("COGNIGY_HTTP_PROXY")?.ifBlank { null }?.let { ProxyBuilder.http(it) }

        val configuredEngine = engine.create {
            this.proxy = proxy
        }
        return Kognigy(configuredEngine, endpointReadyTimeout = endpointReadyTimeout)
    }

    private inline fun withKognigy(
        kognigy: Kognigy = defaultKognigy(),
        sessionId: SessionId? = null,
        userId: UserId? = null,
        block: (Kognigy, KognigySession) -> Unit,
    ) {
        val uri = URLBuilder(Url(System.getenv(ENDPOINT_URL_ENV))).apply {
            parameters.append("kognigy-unit-test", "true")
        }.build()
        val token = System.getenv(ENDPOINT_TOKEN_ENV)

        val randomId = randomUUID().toString()

        val session = KognigySession(
            id = sessionId ?: SessionId(randomId),
            uri,
            EndpointToken(token),
            userId = userId ?: UserId("kognigy-integration-test-${randomId}"),
            ChannelName("kognigy"),
        )

        block(kognigy, session)
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun runTest(
        kognigy: Kognigy = defaultKognigy(),
        sessionId: SessionId? = null,
        userId: UserId? = null,
        delay: Long = 3000,
        iterations: Int = 5,
        newPayload: () -> Pair<String, JsonElement?>,
    ) = withKognigy(kognigy, sessionId, userId) { _, session ->
        logger.info { "Session: $session" }

        val connection = kognigy.connect(session)
        when (connection.connectionSuccessReason.await()) {
            ENDPOINT_READY_SIGNAL -> logger.info { "Connection became ready due to endpoint-ready event" }
            ASSUMED_READY_WITH_TIMEOUT -> logger.info { "Connection became ready after timeout" }
            ASSUMED_READY_WITHOUT_TIMEOUT -> logger.info { "Connection became ready immediately" }
        }

        suspend fun sendInput(input: Pair<String, JsonElement?>) {
            logger.info { "Input: $input" }
            connection.sendInput(input.first, input.second)
        }

        var totalDuration = Duration.ZERO
        var maxDuration = Duration.ZERO
        var minDuration = Duration.INFINITE

        repeat(iterations) {
            val input = newPayload()
            sendInput(input)
            val start = Clock.System.now()
            while (true) {
                val event = withTimeoutOrNull(20.seconds) {
                    connection.output.receive().also {
                        val duration = (Clock.System.now() - start)
                        totalDuration += duration
                        if (duration > maxDuration) {
                            maxDuration = duration
                        }
                        if (duration < minDuration) {
                            minDuration = duration
                        }
                    }
                }
                if (event == null) {
                    fail("Receive timed out!", cause = null)
                }
                logger.info { "Received Event: $event" }
                if (event is CognigyEvent.ProtocolError) {
                    fail("Protocol error detected: $event")
                }
                if (event !is CognigyEvent.Output.Message) {
                    continue
                }

                assertEquals("you said \"${input.first}\"\ndata was \"{}\"", event.data.text)

                delay(delay)

                break
            }
        }

        connection.close()

        val avg = totalDuration / iterations
        logger.info { "run complete - min=$minDuration max=$maxDuration avg=$avg" }
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

    /**
     * The endpoint that is used here should be backed by a flow that simply reproduces a
     * response for each input it gets.
     */
    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun unicodeSymbols() {
        runBlocking {
            runTest(iterations = 1) { "Weird\u2028Symbol" to null }
        }
    }

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Ignore
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
                        logger.info { "######## $iteration. iteration, $activeRuns active runs" }
                    }
                    delay(50.milliseconds)
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

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun properlyCloses() {
        runBlocking(Dispatchers.IO) {
            withKognigy { kognigy, session ->
                val connection = kognigy.connect(session)

                val deferredMessage = CompletableDeferred<CognigyEvent.Output.Message>()
                val receiveJob = launch {
                    while (true) {
                        val result = connection.output.receiveCatching()
                        if (result.isClosed) {
                            break
                        }
                        logger.info { "Received: $result" }
                        result.getOrThrow().let {
                            if (it is CognigyEvent.Output.Message) {
                                deferredMessage.complete(it)
                            }
                        }
                    }
                    logger.info { "Exited!" }
                }

                connection.sendInput(text = "test")
                delay(1.seconds)
                connection.close()
                logger.info { "Closed!" }

                assertEquals("you said \"test\"\ndata was \"{}\"", deferredMessage.await().data.text)

                logger.info { "Awaiting loop receiveJob completion..." }
                receiveJob.join()
            }
        }
    }

    private companion object {
        const val ENDPOINT_URL_ENV = "ENDPOINT_URL"
        const val ENDPOINT_TOKEN_ENV = "ENDPOINT_TOKEN"
    }
}
