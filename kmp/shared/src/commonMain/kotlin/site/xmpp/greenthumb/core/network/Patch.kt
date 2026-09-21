package site.xmpp.greenthumb.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Три состояния PATCH-поля (план Stage 2 п.5; RN-источник `app/plant/[id].tsx:296-331`):
 *  - [Absent] — поле не трогаем (в JSON не пишется вовсе);
 *  - [Value] — задать значение;
 *  - [Null] — сбросить колонку в явный null (серверная семантика
 *    PATCH /api/plants/:id: присутствующее поле обновляется, явный null сбрасывает).
 *
 * Nullable `Patch<T>`: `Value<String?>(null)` сериализуется как `null` —
 * тот же провод, что [Null], но в типе выражено «мы задали null» (уход = не задан).
 */
sealed interface Patch<out T> {

    /** Поле не отправляется в JSON (encodeDefaults=false в [ApiClient.json]). */
    data object Absent : Patch<Nothing>

    /** Задать значение (в т.ч. `Value<String?>(null)` → явный null). */
    data class Value<T>(val value: T) : Patch<T>

    /** Явный null: сбросить поле на сервере. */
    data object Null : Patch<Nothing>

    companion object {
        fun <T> of(value: T?): Patch<T> = if (value == null) Null else Value(value)
    }
}

/**
 * Сериализатор [Patch] (подключён file-level через @UseSerializers в Dtos.kt):
 * Value — как делегированный тип, Null и Value(null) — JSON null,
 * Absent — обрабатывается политикой дефолтов (encodeDefaults=false в
 * [ApiClient.json]): поля-дефолты не пишутся вовсе.
 */
@OptIn(ExperimentalSerializationApi::class)
open class PatchSerializer<T : Any>(private val element: KSerializer<T>) : KSerializer<Patch<T>> {

    override val descriptor: SerialDescriptor = element.descriptor

    override fun serialize(encoder: Encoder, value: Patch<T>) {
        when (value) {
            is Patch.Value<*> ->
                @Suppress("UNCHECKED_CAST")
                if (value.value == null) encoder.encodeNull()
                else element.serialize(encoder, value.value as T)
            is Patch.Null -> encoder.encodeNull()
            is Patch.Absent -> throw IllegalStateException("Absent must not be serialized (encodeDefaults=false)")
        }
    }

    override fun deserialize(decoder: Decoder): Patch<T> =
        if (decoder.decodeNotNullMark()) Patch.Value(element.deserialize(decoder)) else Patch.Null
}
