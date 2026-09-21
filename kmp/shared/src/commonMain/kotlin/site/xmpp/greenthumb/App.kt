package site.xmpp.greenthumb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Тривиальная точка входа UI скелета (Stage 1).
 * Заменяется реальной оболочкой приложения в Stage 6.
 *
 * Кнопка, текстовое поле и скролл-список — минимальная интерактивная поверхность
 * для проверки MCP-инструментов харнесса (click / type_text / scroll) в фиче
 * kmp-desktop-harness; поведенческого смысла не несут.
 */
@Composable
fun App() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            var clickCount by remember { mutableIntStateOf(0) }
            var fieldText by remember { mutableStateOf("") }

            // Заголовок — проба hot reload: правка строки видна в окне после reload.
            Text(text = "GreenThumb", style = MaterialTheme.typography.headlineMedium)
            Text(text = "Clicks: $clickCount", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { clickCount++ }) {
                Text(text = "Click me")
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = fieldText,
                onValueChange = { fieldText = it },
                label = { Text(text = "Type here") },
            )

            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn {
                items((1..50).toList()) { index ->
                    Text(text = "Item $index", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
