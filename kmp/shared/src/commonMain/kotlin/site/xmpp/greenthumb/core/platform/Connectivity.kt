package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Состояние сети (Stage 4 п.8; architecture.md §7): поток «есть сеть / нет
 * сети» для полосы офлайна и третьего триггера координатора обновления
 * ([site.xmpp.greenthumb.data.RefreshCoordinator], VAL-OFF-004).
 *
 * Зачем отдельный источник: `ON_RESUME` не срабатывает, когда сеть вернулась,
 * а приложение всё это время оставалось на экране (kmp-migration-plan.md
 * Stage 4 п.8). RN закрывал это `refetchOnReconnect` React Query
 * (`lib/queryClient.ts:30`); здесь ту же роль играет [isOnline].
 *
 * Состояние «нет сети» — отдельное от «данные несвежие»: полоса офлайна
 * питается отсюда, баннер «обновлено в HH:mm» — от `sync_meta` (M4).
 *
 * Платформенные actual'ы:
 * - androidMain: `ConnectivityManager.registerDefaultNetworkCallback` —
 *   переходы `onAvailable`/`onLost`/`onUnavailable` + проверка
 *   `NET_CAPABILITY_VALIDATED`;
 * - jvmMain: всегда `true` — у desktop-харнесса источника состояния сети нет,
 *   а «офлайн по умолчанию» скрыл бы рабочие экраны (та же логика, что у
 *   jvm-actual'ов [site.xmpp.greenthumb.core.storage.LegacyHandoff] и
 *   `isUpdateInstall`).
 *
 * [isOnline] — «горячее» состояние с текущим значением: подписчик получает
 * его немедленно, не дожидаясь следующего перехода. [close] снимает
 * платформенную подписку (Android: `unregisterNetworkCallback`) и
 * идемпотентен.
 */
public expect class Connectivity(appContext: Any) {
    public val isOnline: StateFlow<Boolean>

    public fun close()
}
