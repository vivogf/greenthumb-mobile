package site.xmpp.greenthumb.ui.screens.profile

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.AppVersion
import site.xmpp.greenthumb.core.platform.Clipboard
import site.xmpp.greenthumb.core.platform.OpenUrl
import site.xmpp.greenthumb.core.platform.PushOutcome
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.core.storage.ThemePreference
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtBellOutlineMark
import site.xmpp.greenthumb.ui.components.GtCheckMark
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtClockMark
import site.xmpp.greenthumb.ui.components.GtContrastMark
import site.xmpp.greenthumb.ui.components.GtCopyMark
import site.xmpp.greenthumb.ui.components.GtGlobeMark
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtKeyMark
import site.xmpp.greenthumb.ui.components.GtLockMark
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtMoonMark
import site.xmpp.greenthumb.ui.components.GtPaletteMark
import site.xmpp.greenthumb.ui.components.GtPersonMark
import site.xmpp.greenthumb.ui.components.GtRefreshMark
import site.xmpp.greenthumb.ui.components.GtSendMark
import site.xmpp.greenthumb.ui.components.GtSignOutMark
import site.xmpp.greenthumb.ui.components.GtSunMark
import site.xmpp.greenthumb.ui.components.GtSwitch
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.handoffModal_copy
import site.xmpp.greenthumb.ui.res.privacy_aboutBody
import site.xmpp.greenthumb.ui.res.privacy_aboutTitle
import site.xmpp.greenthumb.ui.res.privacy_bugBody
import site.xmpp.greenthumb.ui.res.privacy_bugTitle
import site.xmpp.greenthumb.ui.res.profile_anonymous
import site.xmpp.greenthumb.ui.res.profile_appDescription
import site.xmpp.greenthumb.ui.res.profile_chooseLanguage
import site.xmpp.greenthumb.ui.res.profile_chooseTheme
import site.xmpp.greenthumb.ui.res.profile_generateNewKey
import site.xmpp.greenthumb.ui.res.profile_generating
import site.xmpp.greenthumb.ui.res.profile_hide
import site.xmpp.greenthumb.ui.res.profile_keyRegenerated
import site.xmpp.greenthumb.ui.res.profile_keyRegeneratedHint
import site.xmpp.greenthumb.ui.res.profile_language
import site.xmpp.greenthumb.ui.res.profile_languageEnglish
import site.xmpp.greenthumb.ui.res.profile_languageRussian
import site.xmpp.greenthumb.ui.res.profile_notificationTime
import site.xmpp.greenthumb.ui.res.profile_notificationTimeHint
import site.xmpp.greenthumb.ui.res.profile_notificationTimeUpdated
import site.xmpp.greenthumb.ui.res.profile_notifications
import site.xmpp.greenthumb.ui.res.profile_notificationsDisabled
import site.xmpp.greenthumb.ui.res.profile_notificationsDisabledHint
import site.xmpp.greenthumb.ui.res.profile_notificationsEnabled
import site.xmpp.greenthumb.ui.res.profile_notificationsEnabledHint
import site.xmpp.greenthumb.ui.res.profile_notificationsHint
import site.xmpp.greenthumb.ui.res.profile_pushPermissionDenied
import site.xmpp.greenthumb.ui.res.profile_recoveryKey
import site.xmpp.greenthumb.ui.res.profile_recoveryKeyHint
import site.xmpp.greenthumb.ui.res.profile_regenerateWarning
import site.xmpp.greenthumb.ui.res.profile_settingsSaved
import site.xmpp.greenthumb.ui.res.profile_show
import site.xmpp.greenthumb.ui.res.profile_signOut
import site.xmpp.greenthumb.ui.res.profile_signOutConfirm
import site.xmpp.greenthumb.ui.res.profile_signOutWarning
import site.xmpp.greenthumb.ui.res.profile_saving
import site.xmpp.greenthumb.ui.res.profile_testNotification
import site.xmpp.greenthumb.ui.res.profile_testSent
import site.xmpp.greenthumb.ui.res.profile_testSentHint
import site.xmpp.greenthumb.ui.res.profile_theme
import site.xmpp.greenthumb.ui.res.profile_themeAuto
import site.xmpp.greenthumb.ui.res.profile_themeDark
import site.xmpp.greenthumb.ui.res.profile_themeLight
import site.xmpp.greenthumb.ui.res.profile_title
import site.xmpp.greenthumb.ui.theme.GreenThumbColors
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/** Адрес поддержки — тот же, что у choose-режима логина (RN lib/constants.ts). */
private const val SUPPORT_EMAIL: String = "greenthumb.taunt861@passmail.net"

/**
 * Высота списка пикера времени: RN maxHeight: '70%' экрана (RN-окно ~390dp
 * ширины, ~844 высоты → ~590dp; ближайшие шаги шкалы [Spacing] — 24×25=600).
 * Абсолютная высота: список скроллится, точный потолок не критичен.
 */
private val TimePickerListHeight: Dp = Spacing.xxl * 25

/**
 * Экран профиля (Stage 7 п.4, фича screen-profile) — порт
 * `app/(tabs)/profile.tsx` (736 строк, прочитана целиком). Секции 1:1:
 *
 * - аккаунт-карточка (имя/аноним + подзаголовок приложения);
 * - уведомления: тумблер (состояние подписки [PushTokens.subscriptionStatus],
 *   RN checkExpoSubscription на mount; недоступно → off), время уведомления
 *   (пикер целых часов 00:00–23:00, PATCH `HH:00`, no-op на тот же час,
 *   активен только при включённых пулах — RN `onPress={pushEnabled ? …}`),
 *   тестовое уведомление (только при включённых; активная реализация M9);
 * - язык (пикер-модалка, чекмарк на текущем, немедленное применение —
 *   RN changeLanguage; VAL-I18N-004/006);
 * - тема (пикер light/dark/auto, чекмарк, немедленное применение — RN
 *   setThemePreference; VAL-THEME-002);
 * - recovery key (show/hide, копирование с «скопировано» на 2 с, регенерация
 *   с подтверждающим диалогом — [SessionManager.regenerateRecoveryKey],
 *   новый ключ сохранён и показан — VAL-PROFILE-001);
 * - About & Privacy (текст + support email mailto через [OpenUrl]);
 * - выход (диалог подтверждения → [SessionManager.signOut] — полная
 *   локальная чистка M4, VAL-PROFILE-006);
 * - версия внизу ([AppVersion.name], RN Constants.expoConfig?.version —
 *   VAL-PROFILE-007).
 *
 * Отличия от RN, осознанные по parity-файлу:
 * - Ionicons → Canvas-метки (material-icons в пинах нет, прецедент
 *   GtBottomTabs); иконка копирования [GtIconButton] — как show-key;
 * - `colors.primary + '22'` (альфа-хвост строки) → primaryContainer
 *   (прецедент welcome/login); скопированный checkmark —
 *   [GreenThumbColors.success] (`#22c55e` RN);
 * - RN-гаптика (Haptics) не переносится: expect-слой M10, desktop no-op;
 * - RN-ветка isExpoGo («Requires development build») не переносится:
 *   KMP всегда standalone-сборка;
 * - текст предупреждения регенерации — RN-хардкод (англ. строка в исходнике
 *   profile.tsx) заводится ключом `profile.regenerateWarning` в обеих локалях
 *   (RN-хардкод — не баг паритета, а пробел i18n; по решению Stage 7 ключи
 *   портируются);
 * - pushLanguage — лямбда (значение на момент действия): wire-значение
 *   AppLanguage ('ru'/'en'); RN-фолбэк 'ru' при системной локали решает
 *   вызывающий ([site.xmpp.greenthumb.App]), экран не пересчитывает.
 *
 * Экран живёт под [AppLocalizedContent] (GtAppNavGraph): смена языка
 * перекомпоновывает remember-состояние вместе с подписями (VAL-I18N-006).
 */
@Composable
public fun ProfileScreen(
    session: SessionManager,
    settings: AppPreferencesStore,
    push: PushTokens,
    pushLanguage: () -> String,
    onAddPlant: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // Пользователь из реактивного состояния сессии: applyUser после
    // PATCH/регенерации обновляет и подписи экрана, и cached_user.
    val sessionState by session.state.collectAsState()
    val user = (sessionState as? SessionState.SignedIn)?.user

    // Локальные состояния RN profile.tsx (порядок как у RN).
    var keyVisible by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var regenerating by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var pushEnabled by remember { mutableStateOf(false) }
    var pushLoading by remember { mutableStateOf(true) }
    var pushToggling by remember { mutableStateOf(false) }
    var testSending by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var savingTime by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<SuccessEvent?>(null) }

    val themePreference by settings.theme.collectAsState(initial = null)
    val languagePreference by settings.language.collectAsState(initial = null)

    // Статус подписки на mount (RN profile.tsx:60-73); недоступен → off.
    LaunchedEffect(user?.id) {
        if (user == null) return@LaunchedEffect
        pushLoading = true
        pushEnabled = try {
            push.subscriptionStatus()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // RN checkExpoSubscription().catch(() => setPushEnabled(false)).
            false
        }
        pushLoading = false
    }

    // Тексты модалок — до корутин (stringResource композабелен, catch — нет).
    val errorTitle = stringResource(Res.string.common_error)
    val settingsSavedTitle = stringResource(Res.string.profile_settingsSaved)
    val timeUpdatedText = stringResource(Res.string.profile_notificationTimeUpdated)
    val keyRegeneratedTitle = stringResource(Res.string.profile_keyRegenerated)
    val keyRegeneratedHint = stringResource(Res.string.profile_keyRegeneratedHint)
    val testSentTitle = stringResource(Res.string.profile_testSent)
    val testSentHint = stringResource(Res.string.profile_testSentHint)
    val notificationsEnabledTitle = stringResource(Res.string.profile_notificationsEnabled)
    val notificationsEnabledHint = stringResource(Res.string.profile_notificationsEnabledHint)
    val notificationsDisabledTitle = stringResource(Res.string.profile_notificationsDisabled)
    val notificationsDisabledHint = stringResource(Res.string.profile_notificationsDisabledHint)
    val permissionDeniedText = stringResource(Res.string.profile_pushPermissionDenied)
    val cancelText = stringResource(Res.string.common_cancel)
    val generateTitle = stringResource(Res.string.profile_generateNewKey)
    val regenerateWarning = stringResource(Res.string.profile_regenerateWarning)
    val signOutTitle = stringResource(Res.string.profile_signOutConfirm)
    val signOutWarning = stringResource(Res.string.profile_signOutWarning)
    val signOutText = stringResource(Res.string.profile_signOut)
    val savingLabel = stringResource(Res.string.profile_saving)

    // Время уведомления: RN notification_time ?? '09:00', целые часы.
    val notificationTime = user?.notificationTime ?: "09:00"
    val currentHour = notificationTime.take(2).toIntOrNull()?.coerceIn(0, 23) ?: 0
    val currentTimeLabel = hourLabel(currentHour)

    // ------------------------------------------------------------------
    // Секции
    // ------------------------------------------------------------------

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
    ) {
        Text(
            text = stringResource(Res.string.profile_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))

        // Аккаунт (RN SectionCard 1).
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl * 2)
                        .background(scheme.primaryContainer, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center,
                ) {
                    GtPersonMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xxl))
                }
                Column {
                    Text(
                        text = user?.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.profile_anonymous),
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.profile_appDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Уведомления (RN SectionCard 2: тумблер, время, тест).
        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                GtBellOutlineMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.profile_notifications),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.profile_notificationsHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (pushLoading) {
                    CircularProgressIndicator(
                        color = scheme.primary,
                        modifier = Modifier.size(Spacing.xl),
                    )
                } else {
                    GtSwitch(
                        checked = pushEnabled,
                        onCheckedChange = { value ->
                            if (pushToggling) return@GtSwitch
                            scope.launch {
                                pushToggling = true
                                try {
                                    if (value) {
                                        // RN-ветка enable: разрешение + подписка.
                                        // Каркас: исход решает PushTokens (M9).
                                        when (val outcome = push.requestSubscribe(pushLanguage())) {
                                            is PushOutcome.Subscribed -> {
                                                pushEnabled = true
                                                success = SuccessEvent.NotificationsEnabled
                                            }
                                            is PushOutcome.Denied -> errorText = permissionDeniedText
                                            is PushOutcome.Error -> errorText = outcome.message
                                        }
                                    } else {
                                        push.unsubscribe()
                                        pushEnabled = false
                                        success = SuccessEvent.NotificationsDisabled
                                    }
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (e: ApiError) {
                                    errorText = e.message
                                } finally {
                                    pushToggling = false
                                }
                            }
                        },
                        enabled = !pushToggling,
                        contentDescription = stringResource(Res.string.profile_notifications),
                    )
                }
            }
            RowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        enabled = pushEnabled && !savingTime,
                    ) { if (pushEnabled) showTimePicker = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                GtClockMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.profile_notificationTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pushEnabled) scheme.onSurface else scheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.profile_notificationTimeHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (savingTime) stringResource(Res.string.profile_saving) else currentTimeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                if (pushEnabled) {
                    GtChevronMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
                }
            }
            if (pushEnabled) {
                RowDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            enabled = !testSending,
                        ) {
                            if (testSending) return@clickable
                            scope.launch {
                                testSending = true
                                try {
                                    push.sendLocalTestNotification()
                                    success = SuccessEvent.TestSent
                                } finally {
                                    testSending = false
                                }
                            }
                        }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    GtSendMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
                    Text(
                        text = if (testSending) stringResource(Res.string.profile_saving)
                        else stringResource(Res.string.profile_testNotification),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
            }
        }

        // Язык (RN SectionCard 3).
        SectionCard {
            PickerRow(
                icon = { GtGlobeMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl)) },
                label = stringResource(Res.string.profile_language),
                value = when (languagePreference) {
                    AppLanguage.Ru -> stringResource(Res.string.profile_languageRussian)
                    AppLanguage.En, null -> stringResource(Res.string.profile_languageEnglish)
                },
                onClick = { showLanguagePicker = true },
            )
        }

        // Тема (RN SectionCard 4).
        SectionCard {
            PickerRow(
                icon = { GtPaletteMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl)) },
                label = stringResource(Res.string.profile_theme),
                value = when (themePreference) {
                    ThemePreference.Light -> stringResource(Res.string.profile_themeLight)
                    ThemePreference.Dark -> stringResource(Res.string.profile_themeDark)
                    ThemePreference.Auto, null -> stringResource(Res.string.profile_themeAuto)
                },
                onClick = { showThemePicker = true },
            )
        }

        // Recovery key (RN SectionCard 4).
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GtKeyMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
                    Spacer(modifier = Modifier.size(Spacing.xs + Spacing.xxs))
                    Text(
                        text = stringResource(Res.string.profile_recoveryKey),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            if (keyVisible) Res.string.profile_hide else Res.string.profile_show,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) { keyVisible = !keyVisible }
                            .padding(Spacing.xxs),
                    )
                }
                Text(
                    text = stringResource(Res.string.profile_recoveryKeyHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                if (keyVisible && user != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(scheme.surfaceVariant, RoundedCornerShape(Radii.md))
                            .borderHairline(scheme.outline, RoundedCornerShape(Radii.md))
                            .padding(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = user.recoveryKey,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        GtIconButton(
                            onClick = {
                                Clipboard.copy(user.recoveryKey)
                                copied = true
                                scope.launch {
                                    delay(2000)
                                    copied = false
                                }
                            },
                            contentDescription = stringResource(Res.string.handoffModal_copy),
                        ) {
                            if (copied) {
                                GtCheckMark(tint = GreenThumbColors.success, modifier = Modifier.size(Spacing.lg))
                            } else {
                                GtCopyMark(tint = scheme.onSurface, modifier = Modifier.size(Spacing.lg))
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            enabled = !regenerating,
                        ) { if (!regenerating) showRegenerateConfirm = true }
                        .padding(vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    GtRefreshMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg + Spacing.xxs))
                    Text(
                        text = if (regenerating) stringResource(Res.string.profile_generating)
                        else stringResource(Res.string.profile_generateNewKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        // About & Privacy (RN SectionCard 4).
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GtLockMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
                    Spacer(modifier = Modifier.size(Spacing.xs + Spacing.xxs))
                    Text(
                        text = stringResource(Res.string.privacy_aboutTitle),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(Res.string.privacy_aboutBody),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                HorizontalHairline()
                Text(
                    text = stringResource(Res.string.privacy_bugTitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.privacy_bugBody),
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

        // Выход (RN SectionCard 5).
        SectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        enabled = !signingOut,
                    ) { if (!signingOut) showSignOutConfirm = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                GtSignOutMark(tint = scheme.error, modifier = Modifier.size(Spacing.xl))
                Text(
                    text = stringResource(Res.string.profile_signOut),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "GreenThumb v" + AppVersion.name,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // ------------------------------------------------------------------
    // Пикер времени (RN Modal 1: 24 целых часа, чекмарк на текущем,
    // выбор закрывает и PATCH-ит; no-op на тот же час).
    // ------------------------------------------------------------------

    if (showTimePicker) {
        GtModal(onDismissRequest = { showTimePicker = false }) {
            Column(modifier = Modifier.padding(Spacing.xxl)) {
                Text(
                    text = stringResource(Res.string.profile_notificationTime),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                // RN: ScrollView внутри maxHeight: '70%' экрана — 24 опции
                // не помещаются в окно целиком.
                Column(
                    modifier = Modifier
                        .heightIn(max = TimePickerListHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    (0..23).forEach { hour ->
                        val label = hourLabel(hour)
                        val selected = hour == currentHour
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) scheme.primaryContainer else scheme.surfaceVariant,
                                    RoundedCornerShape(Radii.md),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) {
                                    if (savingTime) return@clickable
                                    val newTime = hourLabel(hour)
                                    showTimePicker = false
                                    if (newTime == currentTimeLabel) return@clickable
                                    scope.launch {
                                        savingTime = true
                                        try {
                                            session.updateNotificationTime(newTime)
                                            success = SuccessEvent.TimeSaved
                                        } catch (cancellation: CancellationException) {
                                            throw cancellation
                                        } catch (e: ApiError) {
                                            errorText = e.message
                                        } finally {
                                            savingTime = false
                                        }
                                    }
                                }
                                // RN accessibilityState={{selected}} (пикер
                                // времени app/(tabs)/profile.tsx:578).
                                .semantics { this.selected = selected }
                                .padding(Spacing.md + Spacing.xxs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurface,
                            )
                            if (selected) {
                                GtCheckMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Пикер языка (RN Modal 2: en/ru, чекмарк, немедленное применение).
    // ------------------------------------------------------------------

    if (showLanguagePicker) {
        GtModal(onDismissRequest = { showLanguagePicker = false }) {
            Column(modifier = Modifier.padding(Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(Res.string.profile_chooseLanguage),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                val currentLanguage = languagePreference
                listOf(
                    AppLanguage.En to stringResource(Res.string.profile_languageEnglish),
                    AppLanguage.Ru to stringResource(Res.string.profile_languageRussian),
                ).forEach { (language, label) ->
                    val selected = (currentLanguage ?: AppLanguage.En) == language
                    OptionRow(
                        label = label,
                        selected = selected,
                        onClick = {
                            showLanguagePicker = false
                            scope.launch { settings.setLanguage(language) }
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Пикер темы (RN Modal 3: auto/light/dark, чекмарк).
    // ------------------------------------------------------------------

    if (showThemePicker) {
        GtModal(onDismissRequest = { showThemePicker = false }) {
            Column(modifier = Modifier.padding(Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(Res.string.profile_chooseTheme),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                val resolvedTheme = themePreference ?: ThemePreference.Auto
                val themeOptions: List<Triple<ThemePreference, String, @Composable () -> Unit>> =
                    listOf(
                        Triple(
                            ThemePreference.Auto,
                            stringResource(Res.string.profile_themeAuto),
                            { GtContrastMark(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Spacing.xl)) },
                        ),
                        Triple(
                            ThemePreference.Light,
                            stringResource(Res.string.profile_themeLight),
                            { GtSunMark(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Spacing.xl)) },
                        ),
                        Triple(
                            ThemePreference.Dark,
                            stringResource(Res.string.profile_themeDark),
                            { GtMoonMark(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Spacing.xl)) },
                        ),
                    )
                themeOptions.forEach { (preference, label, icon) ->
                    OptionRow(
                        label = label,
                        selected = resolvedTheme == preference,
                        leadingIcon = icon,
                        onClick = {
                            showThemePicker = false
                            scope.launch { settings.setTheme(preference) }
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Диалоги (RN showAlert): регенерация, выход, ошибки/успех.
    // ------------------------------------------------------------------

    if (showRegenerateConfirm) {
        GtAlertDialog(
            title = generateTitle,
            message = regenerateWarning,
            buttons = listOf(
                GtAlertButton(text = cancelText, style = GtAlertButtonStyle.Cancel),
                GtAlertButton(
                    text = generateTitle,
                    style = GtAlertButtonStyle.Destructive,
                    onClick = {
                        scope.launch {
                            regenerating = true
                            try {
                                session.regenerateRecoveryKey()
                                keyVisible = true
                                success = SuccessEvent.KeyRegenerated
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (e: ApiError) {
                                errorText = e.message
                            } finally {
                                regenerating = false
                            }
                        }
                    },
                ),
            ),
            onDismissRequest = { showRegenerateConfirm = false },
        )
    }

    if (showSignOutConfirm) {
        GtAlertDialog(
            title = signOutTitle,
            message = signOutWarning,
            buttons = listOf(
                GtAlertButton(text = cancelText, style = GtAlertButtonStyle.Cancel),
                GtAlertButton(
                    text = signOutText,
                    style = GtAlertButtonStyle.Destructive,
                    onClick = {
                        signingOut = true
                        scope.launch { session.signOut() }
                    },
                ),
            ),
            onDismissRequest = { showSignOutConfirm = false },
        )
    }

    if (errorText != null) {
        GtAlertDialog(
            title = errorTitle,
            message = errorText,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = { errorText = null },
        )
    }

    // Успех-модалки (RN showAlert(Settings saved, …) / notificationsEnabled /
    // testSent / keyRegenerated): одна за раз (RN алерты не стакаются).
    when (success) {
        SuccessEvent.TimeSaved ->
            SuccessDialog(title = settingsSavedTitle, message = timeUpdatedText) { success = null }
        SuccessEvent.KeyRegenerated ->
            SuccessDialog(title = keyRegeneratedTitle, message = keyRegeneratedHint) { success = null }
        SuccessEvent.TestSent ->
            SuccessDialog(title = testSentTitle, message = testSentHint) { success = null }
        SuccessEvent.NotificationsEnabled ->
            SuccessDialog(title = notificationsEnabledTitle, message = notificationsEnabledHint) { success = null }
        SuccessEvent.NotificationsDisabled ->
            SuccessDialog(title = notificationsDisabledTitle, message = notificationsDisabledHint) { success = null }
        null -> Unit
    }
}

/** Событие успеха (RN showAlert без ошибки): одна модалка за раз. */
private enum class SuccessEvent {
    TimeSaved,
    KeyRegenerated,
    TestSent,
    NotificationsEnabled,
    NotificationsDisabled,
}

@Composable
private fun SuccessDialog(title: String, message: String, onDismiss: () -> Unit) {
    GtAlertDialog(
        title = title,
        message = message,
        buttons = listOf(GtAlertButton(text = "OK")),
        onDismissRequest = onDismiss,
    )
}

// ---------------------------------------------------------------------------
// Секции-обёртки (RN SectionCard / Row / Divider — локальные, 1:1)
// ---------------------------------------------------------------------------

/** Карточка секции: surface + cardBorder, радиус RN 14 → [Radii.lg] (12). */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface, shape)
            .borderHairline(greenThumbExtendedColors().cardBorder, shape)
            .padding(Spacing.lg),
    ) {
        content()
    }
    Spacer(modifier = Modifier.height(Spacing.sm))
}

/** Ряд строки: иконка + лейбл + значение + шеврон (RN Row). */
@Composable
private fun PickerRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onClick() }
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        GtChevronMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
    }
}

/** Разделитель строк карточки (RN Divider marginLeft 50 → отступ [Spacing.xl]). */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(start = Spacing.xxl + Spacing.xs + Spacing.xxs),
    )
}

/** Вертикальная линия между блоками privacy (RN height 1 full width). */
@Composable
private fun HorizontalHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.xxs / 4)
            .background(MaterialTheme.colorScheme.outline),
    )
}

// ---------------------------------------------------------------------------
// Пикеры-опции (общая строка опции с чекмарком — RN Pressable + checkmark-circle)
// ---------------------------------------------------------------------------

/** Строка опции пикера: лейбл (или иконка+лейбл) + чекмарк на выбранной. */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) scheme.primaryContainer else scheme.surfaceVariant,
                RoundedCornerShape(Radii.md),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onClick() }
            // RN accessibilityState={{selected}} (пикеры профиля): чекмарк
            // читается ассистивными поверхностями как состояние выбора.
            .semantics { this.selected = selected }
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            GtCheckMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
        }
    }
}

/** Метка часа `HH:00` (пикер только целых часов, RN-хелпер saveNotificationHour). */
internal fun hourLabel(hour: Int): String {
    val safe = hour.coerceIn(0, 23)
    return (if (safe < 10) "0" else "") + safe + ":00"
}
