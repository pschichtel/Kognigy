package tel.schich.kognigy

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import tel.schich.kognigy.protocol.ChannelName
import tel.schich.kognigy.protocol.EndpointToken
import tel.schich.kognigy.protocol.SessionId
import tel.schich.kognigy.protocol.Source
import tel.schich.kognigy.protocol.UserId

@Serializable
data class KognigySession(
    val id: SessionId,
    @Serializable(with = UrlSerializer::class)
    val endpoint: Url,
    val endpointToken: EndpointToken,
    val userId: UserId,
    val channelName: ChannelName? = null,
    val source: Source = Source.Device,
    val passthroughIp: String? = null,
)