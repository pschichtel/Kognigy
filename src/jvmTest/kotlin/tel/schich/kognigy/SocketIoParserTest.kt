package tel.schich.kognigy

import tel.schich.kognigy.protocol.RawSocketIoFrame
import tel.schich.kognigy.protocol.socketIoParser
import tel.schich.parserkombinator.ParserResult
import tel.schich.parserkombinator.StringSlice
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SocketIoParserTest {
    @BeforeTest
    fun setup() {
        System.setProperty("tel.schich.parser-kombinator.trace", "true")
    }

    @Test
    fun parseConnect() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/", 0, null, ""), StringSlice.of("")), socketIoParser(StringSlice.of("0")))
    }

    @Test
    fun parseNamespacedConnect() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/test", 0, null, "{\"test\":true}"), StringSlice.of("")), socketIoParser(StringSlice.of("0/test,{\"test\":true}")))
    }

    @Test
    fun parseDisconnect() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/", 1, null, "{\"test\":true}"), StringSlice.of("")), socketIoParser(StringSlice.of("1{\"test\":true}")))
    }

    @Test
    fun parseNamespacedDisconnect() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/test", 1, null, ""), StringSlice.of("")), socketIoParser(StringSlice.of("1/test,")))
    }

    @Test
    fun parseEvent() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/", 2, null, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("2[\"test\"]")))
    }

    @Test
    fun parseNamespacedEvent() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/test", 2, null, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("2/test,[\"test\"]")))
    }

    @Test
    fun parseEventWithAckId() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/", 2, 12, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("212[\"test\"]")))
    }

    @Test
    fun parseNamespacedEventWithAckId() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/test", 2, 12, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("2/test,12[\"test\"]")))
    }

    @Test
    fun parseAck() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/", 3, null, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("3[\"test\"]")))
    }

    @Test
    fun parseNamespacedAck() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/test", 3, null, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("3/test,[\"test\"]")))
    }

    @Test
    fun parseConnectError() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/", 4, null, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("4[\"test\"]")))
    }

    @Test
    fun parseNamespacedConnectError() {
        assertEquals(ParserResult.Ok(RawSocketIoFrame("/test", 4, null, "[\"test\"]"), StringSlice.of("")), socketIoParser(StringSlice.of("4/test,[\"test\"]")))
    }
}