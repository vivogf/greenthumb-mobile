package site.xmpp.greenthumb.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.SecureWindow
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtQrCode
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.keyQr_hint
import site.xmpp.greenthumb.ui.res.keyQr_imageDescription
import site.xmpp.greenthumb.ui.res.keyQr_screenshotsBlocked
import site.xmpp.greenthumb.ui.res.keyQr_title
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Recovery key as a QR code (architecture §5.3), opened from Profile. The QR
 * holds only the key. The window is secure while this screen is shown, so
 * screenshots and the recents thumbnail come out black.
 */
@Composable
public fun RecoveryKeyQrScreen(
    session: SessionManager,
    onBack: () -> Unit,
) {
    SecureWindow()
    val scheme = MaterialTheme.colorScheme
    val sessionState by session.state.collectAsState()
    val recoveryKey = (sessionState as? SessionState.SignedIn)?.user?.recoveryKey
    val backLabel = stringResource(Res.string.a11y_back)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
            .testTag("keyQr_screen"),
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onBack() }
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            GtChevronMark(tint = scheme.onSurface, modifier = Modifier.size(Spacing.lg))
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = stringResource(Res.string.keyQr_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = stringResource(Res.string.keyQr_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))
        if (recoveryKey != null) {
            GtQrCode(
                text = recoveryKey,
                contentDescription = stringResource(Res.string.keyQr_imageDescription),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = Spacing.xxl * 12)
                    .fillMaxWidth()
                    .testTag("keyQr_code"),
            )
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = stringResource(Res.string.keyQr_screenshotsBlocked),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
