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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.ui.components.DestructiveButton
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtWarningMark
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.deleteAccount_body
import site.xmpp.greenthumb.ui.res.deleteAccount_bulletKey
import site.xmpp.greenthumb.ui.res.deleteAccount_bulletPlants
import site.xmpp.greenthumb.ui.res.deleteAccount_bulletPush
import site.xmpp.greenthumb.ui.res.deleteAccount_confirm
import site.xmpp.greenthumb.ui.res.deleteAccount_deleting
import site.xmpp.greenthumb.ui.res.deleteAccount_title
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Экран подтверждения удаления аккаунта (Stage 12 п.4, фича
 * kmp-account-deletion-ui, VAL-REL-003) — Play-требование для приложений,
 * создающих аккаунты. В RN этой поверхности нет (там удаление — email-путь
 * docs/account-deletion.html): новая функциональность плана, не порт.
 *
 * Полный экран с описанием последствий (растения/фото/заметки и push-
 * подписки удаляются на сервере, ключ перестаёт работать, действие
 * необратимо) и одним необратимым действием: подтверждение →
 * [SessionManager.deleteAccount] (DELETE /api/auth/account → локальная
 * чистка = матрица signOut, VAL-DATA-008). Экран входа открывает сама
 * смена состояния сессии ([SessionState.SignedOut] пересобирает граф
 * в App()), экрану навигация не нужна; при провале ([ApiError]) он
 * остаётся с диалогом ошибки и НЕ тронутым аккаунтом.
 *
 * Оформление — токены ([Spacing]/ColorScheme, геп-правило K5: литералы
 * оформления в экранах пакета ui/screens запрещены); форма «опасного»
 * действия — [DestructiveButton]
 * (контур error, прецедент удаления растения в plant/[id]). Скролл — как у
 * логина (show-key-урок M7): контент обязан прокручиваться при крупном
 * шрифте, поэтому weight-распорок в прокручиваемом Column нет.
 */
@Composable
public fun DeleteAccountScreen(
    session: SessionManager,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var deleting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Тексты диалога — до корутин (stringResource композабелен, catch — нет).
    val errorTitle = stringResource(Res.string.common_error)
    val confirmLabel = stringResource(Res.string.deleteAccount_confirm)
    val deletingLabel = stringResource(Res.string.deleteAccount_deleting)
    val cancelLabel = stringResource(Res.string.common_cancel)
    val backLabel = stringResource(Res.string.a11y_back)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
    ) {
        // Назад (прецедент шапки plant/[id]: шеврон, клик без индикации).
        Box(
            modifier = Modifier
                .size(Spacing.xxl)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    enabled = !deleting,
                ) { onBack() }
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            GtChevronMark(tint = scheme.onSurface, modifier = Modifier.size(Spacing.lg))
        }
        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = stringResource(Res.string.deleteAccount_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            GtWarningMark(tint = scheme.error, modifier = Modifier.size(Spacing.xl))
            Text(
                text = stringResource(Res.string.deleteAccount_body),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.lg))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(
                Res.string.deleteAccount_bulletPlants,
                Res.string.deleteAccount_bulletPush,
                Res.string.deleteAccount_bulletKey,
            ).forEach { bullet ->
                Text(
                    text = "•  " + stringResource(bullet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))

        if (deleting) {
            CircularProgressIndicator(
                color = scheme.primary,
                modifier = Modifier
                    .size(Spacing.xxl)
                    .align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
        DestructiveButton(
            text = if (deleting) deletingLabel else confirmLabel,
            onClick = {
                if (deleting) return@DestructiveButton
                scope.launch {
                    deleting = true
                    try {
                        session.deleteAccount()
                        // Успех здесь не обрабатывается: сессия ушла в
                        // SignedOut, граф пересобран на экран входа (App()).
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: ApiError) {
                        // Аккаунт цел: ключ/кэш/база не тронуты менеджером.
                        Haptics.error()
                        errorText = e.message
                    } finally {
                        deleting = false
                    }
                }
            },
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        SecondaryButton(
            text = cancelLabel,
            onClick = onBack,
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
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
}
