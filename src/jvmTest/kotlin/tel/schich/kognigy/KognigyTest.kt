package tel.schich.kognigy

import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.java.Java
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables
import tel.schich.kognigy.protocol.ChannelName
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EndpointToken
import tel.schich.kognigy.protocol.SessionId
import tel.schich.kognigy.protocol.UserId
import java.util.UUID
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.test.assertEquals

private val logger = KotlinLogging.logger {}

class KognigyTest {

    private suspend fun CoroutineScope.runTest() {
        val uri = Url(System.getenv(ENDPOINT_URL_ENV))
        val token = System.getenv(ENDPOINT_TOKEN_ENV)

        val proxy = System.getenv("COGNIGY_HTTP_PROXY")?.ifBlank { null }?.let { ProxyBuilder.http(it) }

        val kognigy = Kognigy(Java, proxyConfig = proxy)

        val session = KognigySession(
            SessionId("${UUID.randomUUID()}"),
            uri,
            EndpointToken(token),
            UserId("kognigy-integration-test-${Random.nextUInt()}"),
            ChannelName("kognigy"),
        )

        logger.info { "Session: $session" }

        val connection = kognigy.connect(session)

        suspend fun sendInput(input: String) {
            logger.info { "Input: $input" }
            connection.sendInput(input)
        }

        sendInput("Start!")

        var counter = 0
        connection.output
            .consumeAsFlow()
            .onEach { event ->
                logger.info { "Received Event: $event" }
            }
            .filterIsInstance<CognigyEvent.Output.Message>()
            .take(5)
            .onEach { event ->
                logger.info { "Received Text: <${event.data.text}>" }
                if (counter++ < 5) {
                    logger.info { "delay" }
                    delay(5000)
                    sendInput("Some text! ${Random.nextUInt()}")
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

        assertEquals(5, counter, "should take exactly 5 events")
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
            runTest()
        }
    }

    @EnabledIfEnvironmentVariables(
        EnabledIfEnvironmentVariable(named = ENDPOINT_URL_ENV, matches = ".*"),
        EnabledIfEnvironmentVariable(named = ENDPOINT_TOKEN_ENV, matches = ".*"),
    )
    @Test
    fun loadTest() {
        val scope = CoroutineScope(SupervisorJob())
        runBlocking {
            (1..100).map {
                scope.async {
                    runTest()
                }
            }.awaitAll()
        }
    }



    private companion object {
        const val ENDPOINT_URL_ENV = "ENDPOINT_URL"
        const val ENDPOINT_TOKEN_ENV = "ENDPOINT_TOKEN"
    }
}
