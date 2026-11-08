package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.PRIVACY_POLICY_URL
import site.xmpp.greenthumb.core.SUPPORT_EMAIL
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.platform.LocalRecoveryKeyVault
import site.xmpp.greenthumb.core.platform.OpenUrl
import site.xmpp.greenthumb.core.platform.SystemBackHandler
import site.xmpp.greenthumb.core.storage.MissingRecoveryKeyException
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtKeyMark
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtLockMark
import site.xmpp.greenthumb.ui.components.GtPersonAddMark
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
import site.xmpp.greenthumb.ui.res.privacy_contactLead
import site.xmpp.greenthumb.ui.res.privacy_loginHint
import site.xmpp.greenthumb.ui.res.privacy_moreLink
import site.xmpp.greenthumb.ui.theme.GtParagraph
import site.xmpp.greenthumb.ui.theme.GtType
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Экран входа (Stage 7 п.3, фича screen-login, VAL-LOGIN-001) — порт
 * `app/(auth)/login.tsx` (542 строки, прочитана целиком).
 *
 * РЕЖИМЫ (в графе `auth/login`; RN login.tsx:157, 245, 462):
 * - choose — стартовые две опции: создать аккаунт / войти по ключу + короткая
 *   приватная заметка, ссылка на страницу о данных и support-email (M10 mockup v2);
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

/** Where system Back leads from [mode]; null leaves Back to the system (Choose is the first screen). */
internal fun loginBackTarget(mode: LoginMode): LoginMode? = when (mode) {
    LoginMode.Choose -> null
    LoginMode.Create, LoginMode.Login -> LoginMode.Choose
}

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
    /** Outcome of a saved-key attempt made before this screen (Key not found), shown in login mode. */
    initialNotice: SavedKeyOutcome? = null,
) {
    // Keyed: Key not found can switch the start mode after this screen is already composed.
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    SystemBackHandler(enabled = loginBackTarget(mode) != null) {
        loginBackTarget(mode)?.let { mode = it }
    }
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
            initialNotice = initialNotice,
        )
    }
}

// ---------------------------------------------------------------------------
// Режим choose (RN login.tsx:163-243)
// ---------------------------------------------------------------------------

@Composable
private fun ChooseMode(onChoose: () -> Unit, onLogin: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
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
                GtLeafMark(tint = scheme.secondary, modifier = Modifier.size(Spacing.xxl * 2 + Spacing.xxs))
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

        // Short privacy note (owner mockup v2): text, link to the privacy page, contact line.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Box(modifier = Modifier.padding(top = Spacing.xxs).size(Spacing.xl)) {
                GtLockMark(tint = scheme.onSurfaceVariant, modifier = Modifier.fillMaxSize())
            }
            Column {
                Text(
                    text = stringResource(Res.string.privacy_loginHint),
                    style = GtParagraph.of(GtType.loginHint),
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.privacy_moreLink),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.tertiary,
                    modifier = Modifier
                        .testTag(LoginTags.PRIVACY_LINK)
                        .heightIn(min = Sizes.touch)
                        // No browser on the device: openUri throws ActivityNotFoundException; the tap does nothing.
                        .clickable(role = Role.Button) { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } }
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                Text(
                    text = stringResource(Res.string.privacy_contactLead),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = SUPPORT_EMAIL,
                    style = GtType.loginHint,
                    color = scheme.tertiary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .testTag(LoginTags.SUPPORT_EMAIL)
                        .heightIn(min = Sizes.touch)
                        .clickable(role = Role.Button) { OpenUrl.open("mailto:$SUPPORT_EMAIL") }
                        .wrapContentHeight(Alignment.CenterVertically),
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
        BackButton(onBack = onBack)

        LoginHeader(
            icon = { GtLeafMark(tint = scheme.secondary, modifier = it) },
            title = stringResource(Res.string.login_createTitle),
            subtitle = stringResource(Res.string.login_createSubtitle),
        )

        // Имя (опционально): RN label 14pt + inputStyle (radius 10 → поле).
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(Res.string.login_nameLabel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            GtInputField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(Res.string.login_namePlaceholder),
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
                        // Only the server's key: storage may hold another account's key.
                        // The manager refuses a response without one.
                        onAccountCreated(state.user.recoveryKey ?: throw MissingRecoveryKeyException())
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (e: MissingRecoveryKeyException) {
                        errorText = serviceUnavailableText
                        Haptics.error()
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
                CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xl))
            }
        }

        // One Back control: the labelled chevron at the top (M19).
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
    initialNotice: SavedKeyOutcome?,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val vault = LocalRecoveryKeyVault.current
    var key by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var savedKeyOutcome by remember(initialNotice) { mutableStateOf(initialNotice) }
    val errorTitle = stringResource(Res.string.common_error)
    // Тексты ошибок — ДО корутины (stringResource композабелен; catch — нет).
    val invalidKeyText = stringResource(Res.string.login_errors_invalidRecoveryKey)
    val networkErrorText = stringResource(Res.string.login_errors_networkError)
    val serviceUnavailableText = stringResource(Res.string.login_errors_serviceUnavailable)
    val submit: () -> Unit = submit@{
        val entered = key.trim()
        if (entered.isEmpty() || loading) return@submit
        // Not a key: nothing goes to the server.
        val canonical = canonicalRecoveryKey(entered)
        if (canonical == null) {
            errorText = invalidKeyText
            Haptics.error()
            return@submit
        }
        loading = true
        errorText = null
        scope.launch {
            try {
                // Успех → сессия SignedIn → граф уводит на вкладки
                // (RN useEffect login.tsx:41-45 — автопереход);
                // гаптика Success (RN login.tsx:95).
                session.signInWithRecoveryKey(canonical)
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
    }

    LoginCard {
        BackButton(onBack = onBack)

        LoginHeader(
            icon = { GtKeyMark(tint = scheme.secondary, modifier = it) },
            title = stringResource(Res.string.login_welcomeBack),
            subtitle = stringResource(Res.string.login_enterKey),
        )

        // Поле ключа (RN mono; placeholder uuid-подобный). A key is not prose: no capitals, no autocorrect;
        // Done on the keyboard signs in.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(Res.string.login_recoveryKeyLabel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            GtInputField(
                value = key,
                onValueChange = {
                    key = it
                    errorText = null
                },
                placeholder = stringResource(Res.string.login_recoveryKeyPlaceholder),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }

        // Войти (disabled пока ключ пуст или loading; RN opacity 0.65 →
        // disabled-цвета PrimaryButton).
        PrimaryButton(
            text = if (loading) stringResource(Res.string.login_signingIn) else stringResource(Res.string.login_signIn),
            onClick = submit,
            enabled = !loading && key.isNotBlank(),
            modifier = Modifier.gtButtonWidth(),
        )
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xl))
            }
        }

        SavedKeySignInBlock(
            onClick = {
                if (loading) return@SavedKeySignInBlock
                loading = true
                savedKeyOutcome = null
                scope.launch {
                    val outcome = signInWithSavedKey(vault, session)
                    if (outcome == SavedKeyOutcome.SignedIn) Haptics.success()
                    savedKeyOutcome = outcome
                    loading = false
                }
            },
            outcome = savedKeyOutcome,
            enabled = !loading,
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

/** Test tags of the login screen. */
internal object LoginTags {
    const val ROOT = "login_root"
    const val CARD = "login_card"
    const val PRIVACY_LINK = "login_privacy_link"
    const val SUPPORT_EMAIL = "login_support_email"
}

/**
 * Карточка экрана (mockup v2): 16 dp от краёв экрана, radius [Radii.card], padding 24,
 * gap 20, по центру вертикального скролла (на узких экранах карточка скроллится).
 * Боковые жестовые инсеты не учитываются: карта стоит ровно в 16 dp от края.
 * The keyboard shrinks the scroll viewport (imePadding), so the focused field and the button
 * below it scroll into view instead of hiding under the keyboard.
 */
@Composable
internal fun LoginCard(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .testTag(LoginTags.ROOT)
            .statusBarsPadding()
            .navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Vertical))
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Sizes.screenPadding, vertical = Spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LoginTags.CARD)
                .background(scheme.surface, RoundedCornerShape(Radii.card))
                .borderHairline(greenThumbExtendedColors().cardBorder, RoundedCornerShape(Radii.card))
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

/**
 * Header of the inner login modes: badge, title and subtitle, centred in the card.
 * [icon] receives the modifier with the icon size.
 */
@Composable
internal fun LoginHeader(icon: @Composable (Modifier) -> Unit, title: String, subtitle: String?) {
    val scheme = MaterialTheme.colorScheme
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
            icon(Modifier.size(Spacing.xxl + Spacing.xxs))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The one Back control of create/login (RN Pressable arrow-back, hitSlop 12): a 48 dp box shifted
 * left by its inner margin, so the chevron ink starts on the card content edge.
 */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(Res.string.login_back)
    Box(
        modifier = Modifier
            .offset(x = -(Sizes.touch - Sizes.iconSlot) / 2)
            .size(Sizes.touch)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onBack() },
        contentAlignment = Alignment.Center,
    ) {
        GtChevronMark(
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.iconSlot),
            direction = GtChevronDirection.Left,
        )
    }
}
