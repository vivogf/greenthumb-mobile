package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.platform.OpenUrl
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtKeyMark
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtLockMark
import site.xmpp.greenthumb.ui.components.GtPersonAddMark
import site.xmpp.greenthumb.ui.components.GtTextField
import site.xmpp.greenthumb.ui.components.GtWarningMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.login_back
import site.xmpp.greenthumb.ui.res.login_create
import site.xmpp.greenthumb.ui.res.login_createAccount
import site.xmpp.greenthumb.ui.res.login_createSubtitle
import site.xmpp.greenthumb.ui.res.login_createTitle
import site.xmpp.greenthumb.ui.res.login_creating
import site.xmpp.greenthumb.ui.res.login_enterKey
import site.xmpp.greenthumb.ui.res.login_errors_invalidRecoveryKey
import site.xmpp.greenthumb.ui.res.login_errors_networkError
import site.xmpp.greenthumb.ui.res.login_errors_serviceUnavailable
import site.xmpp.greenthumb.ui.res.login_haveKey
import site.xmpp.greenthumb.ui.res.login_importantTitle
import site.xmpp.greenthumb.ui.res.login_nameLabel
import site.xmpp.greenthumb.ui.res.login_namePlaceholder
import site.xmpp.greenthumb.ui.res.login_recoveryKeyLabel
import site.xmpp.greenthumb.ui.res.login_recoveryKeyPlaceholder
import site.xmpp.greenthumb.ui.res.login_signIn
import site.xmpp.greenthumb.ui.res.login_signingIn
import site.xmpp.greenthumb.ui.res.login_subtitle
import site.xmpp.greenthumb.ui.res.login_title
import site.xmpp.greenthumb.ui.res.login_welcomeBack
import site.xmpp.greenthumb.ui.res.privacy_loginHint
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Экран входа (Stage 7 п.3, фича screen-login, VAL-LOGIN-001) — порт
 * `app/(auth)/login.tsx` (542 строки, прочитана целиком).
 *
 * РЕЖИМЫ (в графе `auth/login`; RN login.tsx:157, 245, 462):
 * - choose — стартовые две опции: создать аккаунт / войти по ключу + приватная
 *   заметка с support-email (RN login.tsx:163-243);
 * - create — имя ОПЦИОНАЛЬНО + create-anonymous (RN :245-346); успех → шлюз
 *   показа ключа (RN setMode('show-key'), login.tsx:97-101 — в KMP-оболочке
 *   M7 шлюз = окно после входа [site.xmpp.greenthumb.ui.nav.PostSignInHost],
 *   автопереход на вкладки подавлён как в RN login.tsx:41-45);
 * - login — ввод существующего ключа (RN :462-541); 401 ≠ сервисный сбой
 *   ([explainAuthError], VAL-LOGIN-003).
 *
 * Режим show-key RN — отдельная ветка экрана с копированием; в KMP это окно
 * после входа: [site.xmpp.greenthumb.ui.nav.PostSignInHost] →
 * [ShowKeySurface] (тоже в этом пакете). Шлюз живёт ровно до подтверждения
 * «я сохранил» — VAL-LOGIN-002.
 *
 * Отличия от RN, осознанные по parity-файлу:
 * - SafeAreaView/KeyboardAvoidingView → statusBars/navigationBars/
 *   systemGestures-пэддинги; центрирование + скролл — вертикальный скролл
 *   с гравитацией центра (RN ScrollView flexGrow + justifyContent center);
 * - Ionicons leaf/person-add/key/lock-closed/warning/arrow-back → Canvas-метки
 *   [GtLeafMark]/[GtPersonAddMark]/[GtKeyMark]/[GtLockMark]/[GtWarningMark]/
 *   [GtChevronMark] (material-icons в пинах миссии нет, прецедент GtBottomTabs);
 * - `colors.primary + '22'` (альфа-хвост строки) → `primaryContainer`
 *   (тот же приём, что welcome-слайды/GtEmptyState);
 * - RN-гаптика перенесена (M10, VAL-ANIM-004): успех/ошибка создания и входа →
 *   [Haptics.success]/[Haptics.error], копия ключа в [ShowKeySurface] →
 *   [Haptics.light] (RN notificationAsync/impactAsync); тапы не меняют данные
 *   (fire-and-forget, desktop no-op);
 * - useAlertDialog → [GtAlertDialog] в составе экрана (та же модалка);
 * - Linking.openURL(mailto) → [OpenUrl.open] (expect/actual, desktop —
 *   best-effort no-op при отсутствии обработчика).
 */
public enum class LoginMode { Choose, Create, Login }

/**
 * Экран логина (режимы choose/create/login; RN app/(auth)/login.tsx).
 *
 * [onAccountCreated] — шов шлюза показа ключа: после успешного создания
 * свежий recovery key уходит наверх ([site.xmpp.greenthumb.App] открывает
 * окно после входа). RN-эквивалент — setMode('show-key') внутри экрана
 * (login.tsx:97-101). Навигацию после входа решает граф: смена состояния
 * сессии на SignedIn пересоздаёт его на вкладки (RN useEffect
 * login.tsx:41-45 без show-key).
 */
@Composable
public fun LoginScreen(
    session: SessionManager,
    onAccountCreated: (recoveryKey: String) -> Unit = {},
    initialMode: LoginMode = LoginMode.Choose,
) {
    var mode by remember { mutableStateOf(initialMode) }
    when (mode) {
        LoginMode.Choose -> ChooseMode(onChoose = { mode = LoginMode.Create }, onLogin = { mode = LoginMode.Login })
        LoginMode.Create -> CreateMode(
            session = session,
            onBack = { mode = LoginMode.Choose },
            onAccountCreated = onAccountCreated,
        )
        LoginMode.Login -> LoginKeyMode(
            session = session,
            onBack = { mode = LoginMode.Choose },
        )
    }
}

// ---------------------------------------------------------------------------
// Режим choose (RN login.tsx:163-243)
// ---------------------------------------------------------------------------

@Composable
private fun ChooseMode(onChoose: () -> Unit, onLogin: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LoginCard {
        // Логотип + заголовок + подзаголовок (RN column center, gap 12).
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Круг 72 (RN) с альфа-фоном primary (`primary + '22'` → container).
            Box(
                modifier = Modifier.size(Spacing.xxl * 3).background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GtLeafMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xxl * 2 + Spacing.xxs))
            }
            Text(
                text = stringResource(Res.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // RN lineHeight 22 — дефолт typography без литерала.
            )
        }

        // Кнопки (RN gap 12 → Spacing.sm; person-add / key иконки RN опущены
        // здесь в пользу чистых кнопок дизайн-системы — тексты те же).
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton(
                text = stringResource(Res.string.login_createAccount),
                onClick = onChoose,
                modifier = Modifier.gtButtonWidth(),
            )
            SecondaryButton(
                text = stringResource(Res.string.login_haveKey),
                onClick = onLogin,
                modifier = Modifier.gtButtonWidth(),
            )
        }

        // Приватная заметка + support email (RN login.tsx:382-406: lock 16 +
        // hint 12pt + email-ссылка primary underline; hitSlop → padding).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs),
        ) {
            Box(modifier = Modifier.padding(top = Spacing.xxs).size(Spacing.xl)) {
                GtLockMark(tint = scheme.onSurfaceVariant, modifier = Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = stringResource(Res.string.privacy_loginHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = SUPPORT_EMAIL,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { OpenUrl.open("mailto:$SUPPORT_EMAIL") },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Режим create (RN login.tsx:245-346)
// ---------------------------------------------------------------------------

@Composable
private fun CreateMode(
    session: SessionManager,
    onBack: () -> Unit,
    onAccountCreated: (recoveryKey: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val errorTitle = stringResource(Res.string.common_error)
    // Тексты ошибок — ДО корутины (stringResource композабелен; catch — нет).
    val invalidKeyText = stringResource(Res.string.login_errors_invalidRecoveryKey)
    val networkErrorText = stringResource(Res.string.login_errors_networkError)
    val serviceUnavailableText = stringResource(Res.string.login_errors_serviceUnavailable)

    LoginCard {
        // Назад (RN Pressable arrow-back, alignSelf start, hitSlop 12).
        BackButton(onBack = onBack)

        // Шапка (RN column center gap 10 → Spacing.sm; круг 64, leaf 30).
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(Spacing.xxl * 2 + Spacing.xxs)
                    .background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GtLeafMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xxl + Spacing.xxs))
            }
            Text(
                text = stringResource(Res.string.login_createTitle),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.login_createSubtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Имя (опционально): RN label 14pt + inputStyle (radius 10 → поле).
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(Res.string.login_nameLabel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            GtTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(Res.string.login_namePlaceholder),
                label = "",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Предупреждение (amber box): importantText + saveIt (bold) + noRestore
        // — склейка с явными пробелами (ImportantMessageTest; ru-ресурс
        // начинается с пробела — пробел задаёт Kotlin-склейка, не ридер).
        WarningBox(
            icon = { GtWarningMark(tint = greenThumbExtendedColors().amber, modifier = Modifier.size(Spacing.xl)) },
            title = stringResource(Res.string.login_importantTitle),
            body = buildImportantMessage(),
            titleColor = greenThumbExtendedColors().amber,
        )

        // Создать (loading → disabled + спиннер; RN opacity 0.75 → disabled).
        PrimaryButton(
            text = if (loading) stringResource(Res.string.login_creating) else stringResource(Res.string.login_create),
            onClick = {
                if (loading) return@PrimaryButton
                loading = true
                errorText = null
                scope.launch {
                    try {
                        // RN: имя trim'ится, пустое → undefined (сервер ставит
                        // дефолт) — login.tsx:104 `name.trim() || undefined`.
                        // Имя обязано доехать до провода: непустое едет в
                        // create-anonymous, пустое опускает поле (VAL-LOGIN-002).
                        val state = session.createAnonymousAccount(name.trim().ifEmpty { null }) as SessionState.SignedIn
                        // Шлюз показа ключа (RN setMode('show-key') + гаптика
                        // Success login.tsx:81; навигацию решает граф).
                        Haptics.success()
                        onAccountCreated(state.user.recoveryKey)
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (e: ApiError) {
                        errorText = explainAuthError(
                            e,
                            isLogin = false,
                            invalidKeyText = invalidKeyText,
                            networkErrorText = networkErrorText,
                            serviceUnavailableText = serviceUnavailableText,
                        )
                        // RN notificationAsync(Error) рядом с алертом (login.tsx:84).
                        Haptics.error()
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.gtButtonWidth(),
        )
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary, modifier = Modifier.size(Spacing.xl))
            }
        }

        // Назад (RN текст mutedForeground по центру).
        Text(
            text = stringResource(Res.string.login_back),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onBack() }
                .padding(vertical = Spacing.xxs),
        )
    }

    // Модалка ошибки (RN showAlert(common.error, explainAuthError(error, false))).
    if (errorText != null) {
        GtAlertDialog(
            title = errorTitle,
            message = errorText,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = { errorText = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Режим login (RN login.tsx:462-541)
// ---------------------------------------------------------------------------

@Composable
private fun LoginKeyMode(
    session: SessionManager,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val errorTitle = stringResource(Res.string.common_error)
    // Тексты ошибок — ДО корутины (stringResource композабелен; catch — нет).
    val invalidKeyText = stringResource(Res.string.login_errors_invalidRecoveryKey)
    val networkErrorText = stringResource(Res.string.login_errors_networkError)
    val serviceUnavailableText = stringResource(Res.string.login_errors_serviceUnavailable)

    LoginCard {
        // Назад.
        BackButton(onBack = onBack)

        // Шапка: key-иконка, welcome back, enter key (RN gap 10).
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(Spacing.xxl * 2 + Spacing.xxs)
                    .background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GtKeyMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xxl + Spacing.xxs))
            }
            Text(
                text = stringResource(Res.string.login_welcomeBack),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.login_enterKey),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Поле ключа (RN mono; placeholder uuid-подобный).
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(Res.string.login_recoveryKeyLabel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            GtTextField(
                value = key,
                onValueChange = {
                    key = it
                    errorText = null
                },
                placeholder = stringResource(Res.string.login_recoveryKeyPlaceholder),
                label = "",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Войти (disabled пока ключ пуст или loading; RN opacity 0.65 →
        // disabled-цвета PrimaryButton).
        PrimaryButton(
            text = if (loading) stringResource(Res.string.login_signingIn) else stringResource(Res.string.login_signIn),
            onClick = {
                val entered = key.trim()
                if (entered.isEmpty() || loading) return@PrimaryButton
                loading = true
                errorText = null
                scope.launch {
                    try {
                        // Успех → сессия SignedIn → граф уводит на вкладки
                        // (RN useEffect login.tsx:41-45 — автопереход);
                        // гаптика Success (RN login.tsx:95).
                        session.signInWithRecoveryKey(entered)
                        Haptics.success()
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (e: ApiError) {
                        errorText = explainAuthError(
                            e,
                            isLogin = true,
                            invalidKeyText = invalidKeyText,
                            networkErrorText = networkErrorText,
                            serviceUnavailableText = serviceUnavailableText,
                        )
                        // RN notificationAsync(Error) рядом с алертом (login.tsx:99).
                        Haptics.error()
                        loading = false
                    }
                }
            },
            enabled = !loading && key.isNotBlank(),
            modifier = Modifier.gtButtonWidth(),
        )
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary, modifier = Modifier.size(Spacing.xl))
            }
        }

        // Назад.
        Text(
            text = stringResource(Res.string.login_back),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onBack() }
                .padding(vertical = Spacing.xxs),
        )
    }

    // Модалка ошибки (RN showAlert(common.error, explainAuthError(error, true))).
    if (errorText != null) {
        GtAlertDialog(
            title = errorTitle,
            message = errorText,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = { errorText = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Общие части (RN login.tsx:112-161)
// ---------------------------------------------------------------------------

/**
 * Карточка экрана: RN cardStyle (radius 16 = Radii.xl, padding 24 = Spacing.xxl,
 * gap 20 = Spacing.xl) на фоне с центрированием (RN Container: SafeAreaView +
 * ScrollView flexGrow/center + padding 20). Скролл вертикальный — на узких
 * экранах карточка скроллится, RN вёл себя так же.
 */
@Composable
private fun LoginCard(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .systemGesturesPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface, RoundedCornerShape(Radii.xl))
                .borderHairline(greenThumbExtendedColors().cardBorder, RoundedCornerShape(Radii.xl))
                .padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            content = { content() },
        )
    }
}

/** Amber-предупреждение create: иконка + заголовок + склеенный текст (RN login.tsx:287-306). */
@Composable
internal fun WarningBox(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    titleColor: androidx.compose.ui.graphics.Color,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(extended.amberBg, RoundedCornerShape(Radii.md))
            .borderHairline(extended.amberBorder, RoundedCornerShape(Radii.md))
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(modifier = Modifier.padding(top = Spacing.xxs)) { icon() }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Кнопка «назад» шапки create/login (RN Pressable arrow-back, hitSlop 12). */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onBack() }
            .padding(Spacing.xs),
    ) {
        GtChevronMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.xl))
    }
}
