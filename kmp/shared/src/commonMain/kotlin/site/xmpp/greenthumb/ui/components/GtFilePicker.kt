package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.PickDirEntry
import site.xmpp.greenthumb.core.platform.listDirectory
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.photoPick_fileTitle
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Файловый диалог десктопа (Stage 8 п.1: `jvmMain: файловый диалог`).
 * Реализован внутри приложения (а не Swing `JFileChooser`), потому что:
 * (1) требование фичи — «работает в харнессе», а MCP видит только
 * Compose-поверхность; (2) нативный диалог в этой среде неуправляем
 * (AX-доступ к macOS выключен: `AXIsProcessTrusted=false`, синтетический ввод
 * невозможен). Вызывается из [PhotoPickerHost] через `FilePickBridge`, когда
 * jvm-actual [site.xmpp.greenthumb.core.platform.pickImage] ждёт выбор файла.
 *
 * Жест: тап по каталогу — войти, тап по файлу — выбрать (путь уходит в
 * jvm-actual, он читает байты), Cancel/подложка — отмена (молчит).
 */
@Composable
internal fun GtFilePicker(
    initialPath: String,
    onPicked: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    var entries by remember(initialPath) { mutableStateOf(listDirectory(initialPath)) }

    val titleText = stringResource(Res.string.photoPick_fileTitle)
    val cancelText = stringResource(Res.string.common_cancel)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.scrim)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            )
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Spacing.xxl * 14)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .background(scheme.surface, RoundedCornerShape(Radii.xl))
                .borderHairline(extended.cardBorder, RoundedCornerShape(Radii.xl))
                .padding(vertical = Spacing.sm),
        ) {
            Text(
                text = titleText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.xxs),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = path,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xxs),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(
                modifier = Modifier
                    .heightIn(max = Spacing.xxl * 16)
                    .verticalScroll(rememberScrollState()),
            ) {
                parentPath(path)?.let { parent ->
                    FileRow(label = "../", onClick = {
                        path = parent
                        entries = listDirectory(parent)
                    })
                }
                entries.forEach { entry ->
                    FileRow(
                        label = if (entry.isDirectory) entry.name + "/" else entry.name,
                        onClick = {
                            if (entry.isDirectory) {
                                val next = joinPath(path, entry.name)
                                path = next
                                entries = listDirectory(next)
                            } else {
                                onPicked(joinPath(path, entry.name))
                            }
                        },
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs)) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = cancelText, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FileRow(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun joinPath(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

internal fun parentPath(path: String): String? {
    val trimmed = path.trimEnd('/')
    val index = trimmed.lastIndexOf('/')
    return if (index < 0) null else trimmed.substring(0, index).ifEmpty { "/" }
}
