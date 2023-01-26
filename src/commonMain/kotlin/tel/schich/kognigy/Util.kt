package tel.schich.kognigy

import io.ktor.http.Url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal class UrlSerializer : KSerializer<Url> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Url", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Url = Url(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Url) = encoder.encodeString(value.toString())
}
