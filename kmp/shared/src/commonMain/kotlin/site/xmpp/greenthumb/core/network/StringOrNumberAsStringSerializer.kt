package site.xmpp.greenthumb.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerializationException

/**
 * Строка-или-число на проводе, в модели — [String]. План Stage 2 п.5:
 * формат `id`/`user_id` не подтверждён, число не должно ронять разбор
 * (`GET /api/plants`); RN-источник `app/_layout.tsx:52` (`plant_id: number | string`).
 *
 * Сериализация — всегда строка (текст сохраняется дословно; uuid-идентификаторы
 * растений числом не являются). Primary-конструктор без параметров обязателен:
 * плагин сериализации инстанцирует @Serializable(with=...) сам.
 */
class StringOrNumberAsStringSerializer : KSerializer<String> {

    private val delegate: KSerializer<String> = String.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> throw SerializationException("Expected string or number, got $element")
        }
    }
}
