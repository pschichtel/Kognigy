# Kognigy

Simple [Cognigy](https://www.cognigy.com/) client based on websockets using [ktor](https://ktor.io/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization).

Specifically it is a client for the Socket ([Socket.io](https://socket.io/)) endpoint.

The library implements enough of the socket.io protocol and Cognigy's protocol on top, so that most agent interactions should work without any issues.

## Limitations of this Implementation

 * The engine.io and socket.io protocol layers only support the WebSockets transport.
 * No automatic reconnection mechanism exists.
 * While the Cognigy protocol is fairly simple, there is very little documentation on it and
   significant parts of the implementation are based on reverse engineering of the official webchat module and SocketClient library.
 * Since Cognigy does not use any Binary frames, binary packet support in engine.io and socket.io
   have not been implemented.
 * While all runtime dependencies are intentionally multiplatform capable, no attempt as been
   made to compile this project with Kotlin/JS or Kotlin/Native (Pull Requests welcome!).
   The same goes for Android support.
