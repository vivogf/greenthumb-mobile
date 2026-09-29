package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.Clipboard
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.ui.components.GtCheckMark
import site.xmpp.greenthumb.ui.components.GtCopyMark
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtWarningMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.handoffModal_copy
import site.xmpp.greenthumb.ui.res.login_accountCreated
import site.xmpp.greenthumb.ui.res.login_keySaved
import site.xmpp.greenthumb.ui.res.login_saveKeyNow
import site.xmpp.greenthumb.ui.res.login_saveKeyWarningTitle
import site.xmpp.greenthumb.ui.res.login_yourRecoveryKey
import site.xmpp.greenthumb.ui.theme.GreenThumbColors
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Полный порт show-key (RN `app/(auth)/login.tsx:348-460`): ключ показан
 * РОВНО ОДИН РАЗ, копирование работает (RN copyKey login.tsx:127-136:
 * Clipboard + «скопировано» 2 с + гаптика Light), авто-переход на вкладки
 * ПОДАВЛЁН до подтверждения (RN login.tsx:41-45 — useEffect-гейт:
 * `user && mode !== 'show-key'`), continue → enable-notifications (RN
 * handleContinue login.tsx:88; в KMP окно после входа переключает поверхность,
 * [site.xmpp.greenthumb.ui.nav.PostSignInHost]).
 *
 * Поверхность окна после входа ([site.xmpp.greenthumb.ui.nav.PostSignInHost]):
 * предупреждение «безвозвратность» (fragments-склейка, [ImportantMessageTest]),
 * ключ моноширинно, кнопка-иконка копирования. Ключ уже записан в SecureStore
 * ([site.xmpp.greenthumb.core.storage.SessionManager.applyUser] — серверный
 * ключ), показывать можно без ограничений.
 *
 * Цвета успеха — RN `#22c55e` / `#22c55e22` → роль [GreenThumbColors.success] +
 * `successContainer` (literал RN-экрана портирован ролью темы, без
 * hex-литералов в экране).
 *
 * Корень — как у [LoginCard] (эталон screen-login: RN все режимы живут в
 * одном ScrollView flexGrow/center, login.tsx:167-172): `fillMaxSize` +
 * `verticalScroll`. Show-key — жёсткий шлюз (авто-переход подавлен); без
 * скролла крупный Android-шрифт выталкивает единственную кнопку выхода за
 * экран и запирает пользователя (scrutiny m7, VAL-LOGIN-002). Гейтинг шлюза
 * не меняется: выход только кнопкой «я сохранил».
 */
@Composable
public fun ShowKeySurface(
    recoveryKey: String,
    onContinue: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // RN copied-состояние 2 с (RN setTimeout login.tsx:134).
    var copied by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .systemGesturesPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface, RoundedCornerShape(Radii.xl))
                .borderHairline(greenThumbExtendedColors().cardBorder, RoundedCornerShape(Radii.xl))
                .padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Успех: иконка checkmark-circle #22c55e на `#22c55e22`
            // (GreenThumbColors.success + successContainer, RN login.tsx:353-366).
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
                    GtCheckMark(tint = GreenThumbColors.success, modifier = Modifier.size(Spacing.xxl + Spacing.sm))
                }
                Text(
                    text = stringResource(Res.string.login_accountCreated),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.login_saveKeyNow),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Danger warning (RN destructive + '1A'/'40' фоны login.tsx:322-330 →
            // error-роль: заголовок/иконка scheme.error, фон — scheme.surface;
            // альфа-хвост строки RN в токены не заводится — GtModal-прецедент).
            WarningBox(
                icon = { GtWarningMark(tint = scheme.error, modifier = Modifier.size(Spacing.xl)) },
                title = stringResource(Res.string.login_saveKeyWarningTitle),
                body = buildSaveKeyWarningMessage(),
                titleColor = scheme.error,
            )

            // Ключ + кнопка-иконка копирования (RN login.tsx:395-435: muted
            // фон mono 13pt, копия 44×44 radius 10; copied → checkmark green).
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(Res.string.login_yourRecoveryKey),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = recoveryKey,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = scheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .background(scheme.surfaceVariant, RoundedCornerShape(Radii.md))
                            .borderHairline(scheme.outline, RoundedCornerShape(Radii.md))
                            .padding(Spacing.md),
                    )
                    GtIconButton(
                        onClick = {
                            Clipboard.copy(recoveryKey)
                            // RN copyKey: гаптика Light (login.tsx:107).
                            Haptics.light()
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

            // «Я сохранил» — единственный выход из шлюза (RN handleContinue
            // login.tsx:88 → router.replace на enable-notifications).
            PrimaryButton(
                text = stringResource(Res.string.login_keySaved),
                onClick = onContinue,
                modifier = Modifier.gtButtonWidth(),
            )
        }
    }
}
