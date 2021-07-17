# Kognigy

Simple [Cognigy](https://www.cognigy.com/) client based on websockets using [ktor](https://ktor.io/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization).

Specifically it is a client for the Socket ([Socket.io](https://socket.io/)) endpoint.

The library implements enough of the socket.io protocol and Cognigy's protocol on top, so that most agent interactions should work without any issues.
