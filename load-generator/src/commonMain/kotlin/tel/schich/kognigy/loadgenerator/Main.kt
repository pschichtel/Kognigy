@file:OptIn(ExperimentalTime::class)
@file:Suppress("MagicNumber")

package tel.schich.kognigy.loadgenerator

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.java.Java
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tel.schich.kognigy.ConnectionSuccessReason
import tel.schich.kognigy.Kognigy
import tel.schich.kognigy.KognigySession
import tel.schich.kognigy.protocol.ChannelName
import tel.schich.kognigy.protocol.CognigyEvent
import tel.schich.kognigy.protocol.EndpointToken
import tel.schich.kognigy.protocol.SessionId
import tel.schich.kognigy.protocol.UserId
import java.util.UUID.randomUUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

private suspend fun runTest(
    kognigy: Kognigy,
    session: KognigySession,
    delay: Long = 3000,
    iterations: Int = 5,
) {
    logger.info { "Session: $session" }

    val connection = kognigy.connect(session)
    when (connection.connectionSuccessReason.await()) {
        ConnectionSuccessReason.ENDPOINT_READY_SIGNAL -> logger.info { "Connection became ready due to endpoint-ready event" }
        ConnectionSuccessReason.ASSUMED_READY_WITH_TIMEOUT -> logger.info { "Connection became ready after timeout" }
        ConnectionSuccessReason.ASSUMED_READY_WITHOUT_TIMEOUT -> logger.info { "Connection became ready immediately" }
    }

    suspend fun sendInput(input: Pair<String, JsonElement?>) {
        logger.info { "Input: $input" }
        connection.sendInput(input.first, input.second)
    }

    var totalDuration = Duration.ZERO
    var maxDuration = Duration.ZERO
    var minDuration = Duration.INFINITE

    val resellerToken = randomUUID().toString()
    val customerToken = randomUUID().toString()
    val accountToken = randomUUID().toString()
    val projectToken = randomUUID().toString()
    val dialogId = randomUUID().toString()
    val greetingPayload = """
        {
            "status": "greeting",
            "authToken": "...",
            "dialogId": "$dialogId",
            "timestamp": ${Clock.System.now().toEpochMilliseconds()},
            "callbackUrl": "https://example.org",
            "projectContext": {
                "resellerToken": "$resellerToken",
                "customerToken": "$customerToken",
                "accountToken": "$accountToken",
                "projectToken": "$projectToken"
            },
            "local": "+999123123",
            "remote": "+999321321",
            "language": "en-US",
            "transcriberLanguage": "en-US",
            "synthesizerLanguage": "en-US",
            "callType": "INBOUND",
            "customSipHeaders": {
                "X-Test": ["value"]
            },
            "customerDialogData": {
                "Test": ["value"]
            },
            "projectConfiguration": {
                "recordingsAllowed": true,
                "outboundCallsAllowed": true,
                "trailingTranscriptionsAllowed": false,
                "enabledFeatureFlags": [],
                "transcriberLanguage": "en-US",
                "synthesizerLanguage": "en-US",
                "inactivityTimeout": 10000
            }
        }
    """.trimIndent()
    // send greeting
    sendInput("" to Json.parseToJsonElement(greetingPayload))

    repeat(iterations) {
        val start = Clock.System.now()
        while (true) {
            val receiveTimeout = 20.seconds
            val event = withTimeoutOrNull(receiveTimeout) {
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
                logger.error { "Receive timed out ($receiveTimeout)!" }
                return
            }
            logger.info { "Received Event: $event" }
            if (event is CognigyEvent.ProtocolError) {
                logger.error { "Protocol error detected: $event" }
                return
            }
            if (event !is CognigyEvent.Output.Message) {
                continue
            }

            sendInput("echo: ${event.data.text}" to event.data.data)
            delay(delay)
            break
        }
    }

    val terminationPayload = """
        {
            "status": "termination",
            "authToken": "...",
            "dialogId": "$dialogId",
            "timestamp": ${Clock.System.now().toEpochMilliseconds()},
            "callbackUrl": "https://example.org",
            "projectContext": {
                "resellerToken": "$resellerToken",
                "customerToken": "$customerToken",
                "accountToken": "$accountToken",
                "projectToken": "$projectToken"
            },
            "reason": "callerTerminated",
            "projectConfiguration": {
                "recordingsAllowed": true,
                "outboundCallsAllowed": true,
                "trailingTranscriptionsAllowed": false,
                "enabledFeatureFlags": [],
                "transcriberLanguage": "en-US",
                "synthesizerLanguage": "en-US",
                "inactivityTimeout": 10000
            }
        }
    """.trimIndent()
    sendInput("" to Json.parseToJsonElement(terminationPayload))

    connection.close()

    val avg = totalDuration / iterations
    logger.info { "run complete - min=$minDuration max=$maxDuration avg=$avg" }
}

suspend fun main(args: Array<String>) {
    if (args.isEmpty()) {
        logger.error { "Usage: <endpoint-url> [delay]" }
        logger.error { "" }
        logger.error { "endpoint-url: The Endpoint-URL of a WebChat or Socket.IO Endpoint in Cognigy" }
        logger.error { "delay: The delay between the starts of two consecutive dialogs, e.g. '50 ms. Defaults to '50 ms'" }
        exitProcess(1)
    }
    val delay = args.getOrNull(1)
        ?.let(Duration::parse)
        ?: 50.milliseconds

    val endpointUrl = URLBuilder(args[0]).apply {
        parameters.append("kognigy-unit-test", "true")
    }.build()
    val token = endpointUrl.segments.last()

    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val proxy = System.getenv("HTTP_PROXY")?.ifBlank { null }?.let { ProxyBuilder.http(it) }

    val configuredEngine = Java.create {
        this.proxy = proxy
    }
    val kognigy = Kognigy(configuredEngine, endpointReadyTimeout = Duration.INFINITE)

    val activeRuns = AtomicInteger(0)
    var i = 0

    coroutineScope {
        withContext(dispatcher) {
            val coroutineName = CoroutineName("kognigy-test-run")
            while (true) {
                val iteration = i++
                launch(Dispatchers.IO + coroutineName) {

                    val randomId = randomUUID().toString()

                    val session = KognigySession(
                        id = SessionId(randomId),
                        endpointUrl,
                        EndpointToken(token),
                        userId = UserId("kognigy-integration-test-${randomId}"),
                        ChannelName("kognigy"),
                    )

                    activeRuns.incrementAndGet()
                    try {
                        runTest(kognigy, session)
                    } finally {
                        activeRuns.decrementAndGet()
                    }
                }
                if (iteration % 10 == 0) {
                    logger.info { "######## $iteration. iteration, $activeRuns active runs" }
                }
                delay(delay)
            }
        }
    }
}