package site.xmpp.greenthumb.core.platform

import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Android-актуал kill-switch (Stage 12 п.1, VAL-REL-001) — Firebase Remote
 * Config. Инициализация [FirebaseRemoteConfig] требует инициализированного
 * FirebaseApp — его поднимает FirebaseInitProvider из merged-манифеста
 * (google-services-плагин :androidApp, тот же механизм, что у PushTokens);
 * без конфига — fail-open: выключатель не активен, старт не ломается.
 *
 * Fetch-политика (требование фичи «старт не блокируется надолго»):
 * - fetchTimeout 5 с (дефолт Firebase 60 с убил бы UX заблокированного старта);
 * - minimumFetchInterval: debug — 0 (каждый запуск тянет сеть — проверка
 *   dev-значений), release — 1 час (Remote Config персистит последнюю
 *   АКТИВИРОВАННУЮ активацию, поэтому троттлинг не мешает: заблокированный
 *   остаётся заблокирован и офлайн, разблокировка — при следующем старте);
 * - с внешним deadline 8 с как страховка от зависшей Task'и.
 *
 * Любой сбой → последнее активированное значение (или дефолт
 * [DEFAULT_MIN_SUPPORTED_BUILD]): kill-switch никогда не ломает старт.
 *
 * Дев-оверрайд (VAL-REL-001 «через dev-значения»: менять Remote Config
 * консолью — user-gated): debuggable-сборка читает
 * `files/debug_min_supported_build` (число) ПЕРВЫМ — приоритет над Firebase;
 * в release-сборке (FLAG_DEBUGGABLE сброшен) файл не читается. Рецепт —
 * missionDir/library/kill-switch.md.
 */
public actual open class RemoteKillSwitch {
    public actual constructor()

    /** Конфиг — лениво, ОДИН раз на процесс (settings/defaults не переустанавливаются). */
    private val config: FirebaseRemoteConfig by lazy { createConfig() }

    public actual open suspend fun fetchMinimumSupportedBuild(): Long {
        // Дев-оверрайд — первым: приоритет над Firebase (без сети тоже работает).
        readDebugOverride()?.let { return it }

        val remoteConfig = try {
            config
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IllegalStateException) {
            // FirebaseApp не инициализирован (нет google-services.json/плагина).
            // Fail-open: решение ниже — дефолт, старт не ломаем.
            Log.w(TAG, "Firebase not initialized — kill switch inactive", failure)
            return DEFAULT_MIN_SUPPORTED_BUILD
        }
        return try {
            withTimeout(FETCH_DEADLINE_MILLIS) { fetchAndActivate(remoteConfig) }
            remoteConfig.getLong(KEY_MIN_SUPPORTED_BUILD)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Fetch не удался (нет сети/таймаут): Remote Config отдаёт последнюю
            // активированную активацию, а без неё — заданный дефолт 0 (fail-open:
            // свежая установка без сети никогда не блокируется — ограничение
            // kill-switch, missionDir/library/kill-switch.md).
            Log.w(TAG, "Remote Config fetch failed — using last activated value", failure)
            remoteConfig.getLong(KEY_MIN_SUPPORTED_BUILD)
        }
    }

    /** Конфиг с fetch-политикой и дефолтом: параметра нет — 0 (не активен). */
    private fun createConfig(): FirebaseRemoteConfig {
        val config = FirebaseRemoteConfig.getInstance()
        config.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setFetchTimeoutInSeconds(FETCH_TIMEOUT_SECONDS)
                .setMinimumFetchIntervalInSeconds(minimumFetchIntervalSeconds())
                .build(),
        )
        config.setDefaultsAsync(mapOf(KEY_MIN_SUPPORTED_BUILD to DEFAULT_MIN_SUPPORTED_BUILD))
        return config
    }

    /** Троттлинг fetch'а: debug — каждый старт, release — раз в час. */
    private fun minimumFetchIntervalSeconds(): Long {
        val context = appContextOrNull() ?: return RELEASE_MIN_FETCH_INTERVAL_SECONDS
        val debuggable =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) DEBUG_MIN_FETCH_INTERVAL_SECONDS else RELEASE_MIN_FETCH_INTERVAL_SECONDS
    }

    /**
     * Дев-оверрайд: ТОЛЬКО debuggable-сборка (release файл не читает —
     * content произвольный не парсится в число, мусор = отсутствие override).
     */
    private fun readDebugOverride(): Long? {
        val context = appContextOrNull() ?: return null
        val debuggable =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return null
        val file = File(context.filesDir, DEBUG_OVERRIDE_FILE)
        if (!file.isFile) return null
        return file.readText().trim().toLongOrNull()
    }

    /** Suspend-обёртка Task'и fetchAndActivate (паттерн PushTokens.fcmToken). */
    private suspend fun fetchAndActivate(config: FirebaseRemoteConfig): Boolean =
        suspendCancellableCoroutine { continuation ->
            config.fetchAndActivate()
                .addOnSuccessListener { activated ->
                    if (continuation.isActive) continuation.resume(activated)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    private companion object {
        const val TAG = "RemoteKillSwitch"

        /** Параметр Remote Config (Stage 12 п.1; long; дефолт 0 — см. выше). */
        const val KEY_MIN_SUPPORTED_BUILD = "min_supported_build"

        /** Fetch-таймаут Remote Config (с), дефолт Firebase 60 слишком долог. */
        const val FETCH_TIMEOUT_SECONDS = 5L

        /** Троттлинг в release: час (последняя активация персистится — см. шапку). */
        const val RELEASE_MIN_FETCH_INTERVAL_SECONDS = 3600L

        /** Троттлинг в debug: каждый запуск (проверка dev-значений без ожидания). */
        const val DEBUG_MIN_FETCH_INTERVAL_SECONDS = 0L

        /** Имя дев-оверрайд-файла в filesDir (только debuggable-сборки). */
        const val DEBUG_OVERRIDE_FILE = "debug_min_supported_build"

        /** Внешний deadline всего fetch-шага (fetchTimeout + запас на Task-обвязку). */
        const val FETCH_DEADLINE_MILLIS = 8_000L
    }
}
