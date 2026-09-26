package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable

/**
 * Системная тёмная схема для темы приложения (Stage 6 п.3; architecture.md §3
 * «LocalAppTheme»): чтение из композиции, реактивное на смену системной темы.
 * Ожидаемый потребитель — App(): preference light/dark/auto из AppSettings
 * (`greenthumb_theme`) + это чтение → [site.xmpp.greenthumb.ui.theme.resolveDarkTheme]
 * → GreenThumbTheme. Экраны систему не читают — они внутри MaterialTheme.
 *
 * Платформенные actual'ы:
 * - androidMain: `LocalConfiguration.current.uiMode` + UI_MODE_NIGHT_MASK.
 *   Реактивность — AndroidComposeView.updateConfiguration: при
 *   `android:configChanges="uiMode"` (манифест androidApp) смена uimode не
 *   пересоздаёт Activity, а MutableState-configuration провайдера обновляется,
 *   и чтение в композиции перезапускает его (иначе Activity пересоздалась бы
 *   дефолтом — см. комментарий в AndroidManifest.xml про DataStore-одиночку).
 * - jvmMain: `androidx.compose.foundation.isSystemInDarkTheme()` — desktop-обёртка
 *   над приватным LocalSystemTheme CMP (ProvideSystemTheme опрашивает систему
 *   и обновляет её в окне; skiko `SystemTheme_awtKt.getCurrentSystemTheme`).
 */
@Composable
public expect fun isSystemDarkTheme(): Boolean
