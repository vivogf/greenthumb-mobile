package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.time.Clock
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.RefreshCoordinator
import site.xmpp.greenthumb.data.RefreshOutcome
import site.xmpp.greenthumb.data.SyncBanner
import site.xmpp.greenthumb.data.SyncMetaSource
import site.xmpp.greenthumb.data.UnsavedMutation
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Промежуточный корпус вкладки «Растения» (Stage 6 п.1–2): поверхность данных
 * из скелета Stage 4 — список Room, полоса офлайна, баннер «обновлено в
 * HH:mm», replay журнала, полив/полить все — с добавленными триггерами
 * навигации (тап по растению → `plant/{id}`, «Добавить» → `add-plant`).
 *
 * Временная строка «Offline: …» из скелетного App.kt НЕ переносится
 * (наблюдение M4 user-testing: она оставалась после восстановления сети до
 * рестарта, тогда как полоса [Connectivity] и replay обновляются живьём;
 * новый SessionState-переход для этого не заводится). Поиск, фильтры, режимы
 * отображения, pull-to-refresh и статусы — фича screen-dashboard (Stage 7).
 */
@Composable
public fun DashboardScreen(
    userId: String,
    onlineSession: Boolean,
    connectivity: Connectivity,
    opener: PlantRepositoryOpener,
    onOpenPlant: (String) -> Unit,
    onAddPlant: () -> Unit,
) {
    val repo = remember(userId) { opener.open(userId) }
    // Владелец жизненного цикла репозитория — opener (прод: AccountPlantGate —
    // один инстанс на пользователя на процесс; закрытие соединения и удаление
    // базы — только при выходе/смене аккаунта). Экран общий репозиторий НЕ
    // закрывает: смена вкладки/темы/конфигурации не должна закрывать базу под
    // живым наблюдением (architecture §7) — прежний close на выходе ронял
    // позднюю рекомпозицию FATAL «Database is closed» (трассы user-testing
    // M6, раунд 1).
    //
    // Flow создаётся один раз на экземпляр репозитория: рекомпозиция не
    // пересоздаёт наблюдение и не трогает базу. Ошибка живой базы — видна
    // (наблюдение не маскируется пустым списком); тихо завершается только
    // наблюдение уже закрытого репозитория — защита в самом репозитории.
    val observationFailed = remember(repo) { mutableStateOf(false) }
    val plants by remember(repo) {
        repo.observePlants()
            .onEach { observationFailed.value = false }
            .catch { observationFailed.value = true }
    }.collectAsState(initial = emptyList())
    val unsavedIds by remember(repo) {
        repo.observeUnsavedPlantIds()
            .catch {
                observationFailed.value = true
                emit(emptySet())
            }
    }.collectAsState(initial = emptySet())
    val online by connectivity.isOnline.collectAsState()
    var refreshFailed by remember(userId) { mutableStateOf(false) }
    var banner by remember(userId) { mutableStateOf<SyncBanner?>(null) }
    var sawOffline by remember(userId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repo, onlineSession) {
        if (!onlineSession) {
            banner = repo.syncBanner()
            return@LaunchedEffect
        }
        if (online) {
            val replay = runCatching { repo.replayPending() }
            if (replay.isFailure) refreshFailed = true
        }
        val coordinator = RefreshCoordinator(
            syncMeta = SyncMetaSource { repo.lastSyncedAtMillis() },
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
            refresh = { repo.refresh() },
        )
        if (coordinator.onFirstShow() is RefreshOutcome.Failed) refreshFailed = true
        banner = repo.syncBanner()
        coordinator.attachConnectivity(this, connectivity.isOnline)
    }
    LaunchedEffect(online) {
        if (!online) {
            sawOffline = true
            return@LaunchedEffect
        }
        if (!sawOffline) return@LaunchedEffect
        val replay = runCatching { repo.replayPending() }
        if (replay.isFailure) refreshFailed = true
        banner = repo.syncBanner()
    }

    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(Spacing.lg),
    ) {
        val shown = banner
        if (shown != null && shown.visible && shown.syncedAtLabel != null) {
            val prefix = if (shown.showOfflineIcon) "офлайн · " else ""
            Text(
                text = "${prefix}Обновлено в ${shown.syncedAtLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
        }
        if (refreshFailed) {
            Text(
                text = "Не удалось обновить",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
        }
        if (observationFailed.value) {
            // Интерим-строка, как остальные подписи экрана (Stage 7 переводит
            // экран целиком).
            Text(
                text = "Ошибка чтения локальной базы",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
        }
        SecondaryButton(text = "Добавить растение", onClick = onAddPlant)
        Spacer(modifier = Modifier.height(Spacing.sm))
        if (plants.isEmpty()) {
            Text(
                text = "Список пуст",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            plants.forEach { plant ->
                val mark = if (plant.id in unsavedIds) " · ${UnsavedMutation.LABEL}" else ""
                PlantRow(
                    title = "Plant: ${plant.name} · ${plant.lastWateredDate}$mark",
                    onOpen = { onOpenPlant(plant.id) },
                    onWater = {
                        scope.launch {
                            // Откат снимком делает репозиторий; карточка помечена
                            // «не сохранено» ([UnsavedMutation.LABEL]).
                            runCatching { repo.water(plant.id) }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
            }
        }
        if (onlineSession) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Button(onClick = {
                scope.launch {
                    runCatching { repo.waterAll() }
                }
            }) {
                Text(text = "Полить все")
            }
        }
    }
}

/** Растение интерим-списка: тап открывает карточку, кнопка поливает. */
@Composable
private fun PlantRow(
    title: String,
    onOpen: () -> Unit,
    onWater: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
        }
        Button(onClick = onWater) {
            Text(text = "Полить")
        }
    }
}
