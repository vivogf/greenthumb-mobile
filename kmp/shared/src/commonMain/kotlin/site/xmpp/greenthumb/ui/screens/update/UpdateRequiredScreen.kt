package site.xmpp.greenthumb.ui.screens.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.updateRequired_body
import site.xmpp.greenthumb.ui.res.updateRequired_openStore
import site.xmpp.greenthumb.ui.res.updateRequired_title
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Блокирующий экран обновления (Stage 12 п.1, VAL-REL-001): Remote Config
 * `min_supported_build` выше build number установки — версия отозвана.
 * Каркас — как у KeyNotFoundSurface (полный экран, центр, ширина кнопки
 * общая): заголовок, объяснение (версия не поддерживается), единственное
 * действие — «Обновить в Google Play» ([onOpenStore]; App() подставляет
 * OpenUrl со страницей пакета). Других действий нет: под блокировкой ни
 * сессия, ни данные не видны — обновление обязательное.
 */
@Composable
public fun UpdateRequiredScreen(onOpenStore: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(
                    text = stringResource(Res.string.updateRequired_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.updateRequired_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                PrimaryButton(
                    text = stringResource(Res.string.updateRequired_openStore),
                    onClick = onOpenStore,
                    modifier = Modifier.gtButtonWidth(),
                )
            }
        }
    }
}
