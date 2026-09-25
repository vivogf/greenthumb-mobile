package site.xmpp.greenthumb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.PlantEffect
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.RefreshCoordinator
import site.xmpp.greenthumb.data.UnsavedMutation
import site.xmpp.greenthumb.data.RefreshOutcome
import site.xmpp.greenthumb.data.SyncBanner
import site.xmpp.greenthumb.data.SyncMetaSource

/**
 * Стартовая поверхность сессии (Stage 3 п.4): крутит [SessionManager.startup]
 * в remember-корутине и показывает итог (SignedIn/Offline/SignedOut/ошибка
 * handoff) с кнопкой выхода. Заменяется реальной оболочкой приложения в
 * Stage 6; поведенческая сессия — в [SessionManager] (jvmTest), здесь только
 * маппинг на экраны.
 *
 * Полоса «нет сети» питается от [connectivity] (Stage 4 п.8) — она отдельна
 * от «данные несвежие». Баннер «обновлено в HH:mm» считается от
 * `sync_meta` ([SyncBanner], часы системные на этой поверхности).
 * Список — [PlantRepository.observePlants]: ошибка refresh не заменяет его
 * полноэкранной ошибкой. Здесь же видно, что android-actual
 * ([android.net.ConnectivityManager]) реально отдаёт переходы.
 */
@Composable
fun App(session: SessionManager, connectivity: Connectivity, plants: PlantRepositoryOpener) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val state by session.state.collectAsState()
        val online by connectivity.isOnline.collectAsState()
        // Единственный запуск стартовой последовательности при появлении App:
        // без него state навсегда остаётся null («Session: starting…»).
        LaunchedEffect(Unit) { session.startup() }
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(text = "GreenThumb", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(12.dp))

            if (!online) {
                Text(
                    text = "Нет подключения к интернету",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (val current = state) {
                null -> Text(text = "Session: starting…")
                is SessionState.SignedIn ->
                    Text(
                        text = "Signed in: ${current.user.name ?: "anonymous"} " +
                            "(id=${current.user.id}, key=***********************************…)",
                    )
                is SessionState.Offline ->
                    Text(
                        text = "Offline: ${current.user.name ?: "anonymous"} " +
                            "(id=${current.user.id}) — cached_user; no network",
                    )
                is SessionState.SignedOut -> Text(text = "Signed out: recovery key absent or session invalid")
                is SessionState.KeyNotFound ->
                    // Экран «ключ не найден» (Stage 3 п.6): строка — заголовок
                    // из строк i18n (RU-локаль, полный экран и локализация — Stage 6).
                    Text(
                        text = "Аккаунт не найден на устройстве. " +
                            "Ваш аккаунт хранится на сервере — введите ключ восстановления вручную " +
                            "или создайте новый аккаунт (старый ключ восстановить нельзя).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is SessionState.HandoffImportFailed ->
                    Text(
                        text = "Handoff import failed. Recovery key to copy:\n${current.recoveryKey}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }

            val sessionUserId = when (val current = state) {
                is SessionState.SignedIn -> current.user.id
                is SessionState.Offline -> current.user.id
                else -> null
            }
            if (sessionUserId != null) {
                Spacer(modifier = Modifier.height(12.dp))
                SessionPlants(
                    userId = sessionUserId,
                    onlineSession = state is SessionState.SignedIn,
                    online = online,
                    isOnline = connectivity.isOnline,
                    opener = plants,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (state is SessionState.SignedOut || state is SessionState.KeyNotFound) {
                OutlinedButton(onClick = { session.retryStartup() }) {
                    Text(text = "Retry session")
                }
            }
            if (state != null && state !is SessionState.HandoffImportFailed) {
                Spacer(modifier = Modifier.height(8.dp))
                // Выход — suspend (сеть + DataStore); dev-кнопка крутит в scope.
                val scope = rememberCoroutineScope()
                Button(onClick = { scope.launch { session.signOut() } }) {
                    Text(text = "Sign out")
                }
            }
        }
    }
}

/**
 * Список из Room для скелета (VAL-OFF-001 / VAL-DATA-011). Оболочка Stage 6
 * заменит это экраном. «Другой экран» — смена вкладки: данные уже новые,
 * анимация их не держит.
 */
@Composable
private fun SessionPlants(
    userId: String,
    onlineSession: Boolean,
    online: Boolean,
    isOnline: Flow<Boolean>,
    opener: PlantRepositoryOpener,
) {
    val repo = remember(userId) { opener.open(userId) }
    DisposableEffect(repo) {
        onDispose { repo.close() }
    }
    val plants by repo.observePlants().collectAsState(emptyList())
    val unsavedIds by repo.observeUnsavedPlantIds().collectAsState(emptySet())
    var refreshFailed by remember(userId) { mutableStateOf(false) }
    var banner by remember(userId) { mutableStateOf<SyncBanner?>(null) }
    var effectLabel by remember(userId) { mutableStateOf("") }
    var actionNote by remember(userId) { mutableStateOf("") }
    var otherScreen by remember(userId) { mutableStateOf(false) }
    var sawOffline by remember(userId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repo) {
        repo.effects.collect { effect ->
            effectLabel = when (effect) {
                is PlantEffect.WaterAll -> "Эффект: полив всех (${effect.plantIds.size})"
                is PlantEffect.Water -> "Эффект: полив"
                is PlantEffect.Added -> "Эффект: добавление"
                is PlantEffect.Updated -> "Эффект: изменение"
                is PlantEffect.Deleted -> "Эффект: удаление"
            }
        }
    }
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
        coordinator.attachConnectivity(this, isOnline)
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

    val shown = banner
    if (shown != null && shown.visible && shown.syncedAtLabel != null) {
        val prefix = if (shown.showOfflineIcon) "офлайн · " else ""
        Text(text = "${prefix}Обновлено в ${shown.syncedAtLabel}")
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (refreshFailed) {
        Text(text = "Не удалось обновить")
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (effectLabel.isNotEmpty()) {
        Text(text = effectLabel)
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (actionNote.isNotEmpty()) {
        Text(text = actionNote)
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (otherScreen) {
        Text(text = "Другой экран")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { otherScreen = false }) {
            Text(text = "К списку")
        }
    } else {
        if (plants.isEmpty()) {
            Text(text = "Список пуст")
        } else {
            plants.forEach { plant ->
                val mark = if (plant.id in unsavedIds) " · ${UnsavedMutation.LABEL}" else ""
                Text(text = "Plant: ${plant.name} · ${plant.lastWateredDate}$mark")
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = {
                    scope.launch {
                        actionNote = confirmedSaveError(runCatching { repo.water(plant.id) }.exceptionOrNull())
                    }
                }) {
                    Text(text = "Полить")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (onlineSession) {
            Button(onClick = {
                scope.launch {
                    actionNote = confirmedSaveError(runCatching { repo.waterAll() }.exceptionOrNull())
                }
            }) {
                Text(text = "Полить все")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { otherScreen = true }) {
            Text(text = "Другой экран")
        }
    }
}

/** Сеть и отмена — не ошибка сохранения: карточка уже помечена «не сохранено». */
private fun confirmedSaveError(error: Throwable?): String = when (error) {
    null, is ApiError.Network, is ApiError.Timeout, is CancellationException -> ""
    else -> "Ошибка сохранения"
}
