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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState

/**
 * Стартовая поверхность сессии (Stage 3 п.4): крутит [SessionManager.startup]
 * в remember-корутине и показывает итог (SignedIn/SignedOut/ошибка handoff)
 * с кнопкой выхода. Заменяется реальной оболочкой приложения в Stage 6;
 * поведенческая сессия — в [SessionManager] (jvmTest), здесь только маппинг
 * на экраны.
 */
@Composable
fun App(session: SessionManager) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val state by session.state.collectAsState()
        // Единственный запуск стартовой последовательности при появлении App:
        // без него state навсегда остаётся null («Session: starting…»).
        LaunchedEffect(Unit) { session.startup() }
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(text = "GreenThumb", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(12.dp))

            when (val current = state) {
                null -> Text(text = "Session: starting…")
                is SessionState.SignedIn ->
                    Text(
                        text = "Signed in: ${current.user.name ?: "anonymous"} " +
                            "(id=${current.user.id}, key=${current.user.recoveryKey.take(8)}…)",
                    )
                is SessionState.SignedOut -> Text(text = "Signed out: recovery key absent or session invalid")
                is SessionState.HandoffImportFailed ->
                    Text(
                        text = "Handoff import failed. Recovery key to copy:\n${current.recoveryKey}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (state is SessionState.SignedOut) {
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
