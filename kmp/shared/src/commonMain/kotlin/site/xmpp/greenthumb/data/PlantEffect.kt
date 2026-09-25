package site.xmpp.greenthumb.data

/**
 * Одноразовый эффект мутации для экрана.
 *
 * Репозиторий пишет данные в Room сразу и шлёт эффект отдельно.
 * Экран проигрывает анимацию по событию и не задерживает запись
 * (RN `onSuccess` массового полива ждал 1150 мс перед кэшем —
 * эта связка не переносится, architecture.md §7).
 *
 * Событие не отзывается при откате: данные вернутся через [PlantRepository.observePlants].
 * replay нет — поздний подписчик не должен проигрывать анимацию заново.
 */
public sealed class PlantEffect {
    public data class Water(val plantId: String) : PlantEffect()

    public data class WaterAll(val plantIds: List<String>) : PlantEffect()

    public data class Added(val localId: String) : PlantEffect()

    public data class Updated(val plantId: String) : PlantEffect()

    public data class Deleted(val plantId: String) : PlantEffect()
}
