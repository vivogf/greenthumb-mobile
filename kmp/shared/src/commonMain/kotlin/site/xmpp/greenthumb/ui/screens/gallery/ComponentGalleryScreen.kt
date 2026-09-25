package site.xmpp.greenthumb.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import site.xmpp.greenthumb.ui.components.DestructiveButton
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtAlertDialogHost
import site.xmpp.greenthumb.ui.components.GtCalendarMark
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtChip
import site.xmpp.greenthumb.ui.components.GtDatePickerField
import site.xmpp.greenthumb.ui.components.GtEmptyState
import site.xmpp.greenthumb.ui.components.GtInline
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtImagePickerField
import site.xmpp.greenthumb.ui.components.GtImageSource
import site.xmpp.greenthumb.ui.components.GtSectionHeader
import site.xmpp.greenthumb.ui.components.GtStack
import site.xmpp.greenthumb.ui.components.GtSkeleton
import site.xmpp.greenthumb.ui.components.GtSkeletonMode
import site.xmpp.greenthumb.ui.components.GtTextField
import site.xmpp.greenthumb.ui.components.formatPickerDate
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.components.rememberGtAlertController
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Галерея дизайн-системы для desktop-харнесса. Все отступы — токены Spacing.
 * Переключение темы приходит снаружи, чтобы сменился весь [GreenThumbTheme].
 */
@Composable
fun ComponentGalleryScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val alerts = rememberGtAlertController()
    var primaryClicks by remember { mutableIntStateOf(0) }
    var disabledClicks by remember { mutableIntStateOf(0) }
    var sawPressed by remember { mutableStateOf(false) }
    var dialogResult by remember { mutableStateOf("none") }
    var chip by remember { mutableStateOf("All") }
    var dateValue by remember { mutableStateOf<String?>(null) }
    var languageTag by remember { mutableStateOf("en") }
    var pickResult by remember { mutableStateOf("none") }
    var iconClicks by remember { mutableIntStateOf(0) }
    val today = remember { pickerToday() }

    Box(modifier = Modifier.fillMaxSize().background(scheme.background)) {
        GtStack(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
        ) {
            Text(
                text = "Component gallery",
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
            )
            Text(
                text = if (darkTheme) "Theme is dark" else "Theme is light",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
            GtInline {
                SecondaryButton(
                    text = if (darkTheme) "Use light theme" else "Use dark theme",
                    onClick = { onDarkThemeChange(!darkTheme) },
                )
                SecondaryButton(text = "Close gallery", onClick = onClose)
            }

            GtSectionHeader(title = "Buttons")
            PrimaryButton(
                text = "Primary",
                onClick = { primaryClicks += 1 },
                onPressedChange = { pressed -> if (pressed) sawPressed = true },
                modifier = Modifier.gtButtonWidth(),
            )
            Text(
                text = "Primary clicks: $primaryClicks",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Text(
                text = if (sawPressed) "Primary saw pressed" else "Primary idle",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            PrimaryButton(
                text = "Primary disabled",
                onClick = { disabledClicks += 1 },
                enabled = false,
                modifier = Modifier.gtButtonWidth(),
            )
            Text(
                text = "Disabled clicks: $disabledClicks",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            SecondaryButton(
                text = "Secondary",
                onClick = {},
                modifier = Modifier.gtButtonWidth(),
            )
            DestructiveButton(
                text = "Destructive",
                onClick = {},
                modifier = Modifier.gtButtonWidth(),
            )

            GtSectionHeader(title = "Card")
            GtCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Card body",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
            }

            GtSectionHeader(title = "Fields")
            GtTextField(
                value = "",
                onValueChange = {},
                label = "Name",
                placeholder = "Plant name",
                error = "Enter a name",
            )
            GtTextField(
                value = "Ficus",
                onValueChange = {},
                label = "Valid name",
                placeholder = "Plant name",
            )

            GtSectionHeader(title = "Chips")
            GtInline {
                listOf("All", "Needs water", "Healthy").forEach { label ->
                    GtChip(
                        label = label,
                        selected = chip == label,
                        onClick = { chip = label },
                    )
                }
            }
            Text(
                text = "Chip: $chip",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )

            GtSectionHeader(title = "Icon button")
            GtInline {
                GtIconButton(
                    onClick = { iconClicks += 1 },
                    contentDescription = "Icon add",
                    selected = iconClicks > 0,
                ) {
                    GtCalendarMark(
                        tint = scheme.primary,
                        modifier = Modifier.size(Spacing.xl),
                    )
                }
                GtIconButton(
                    onClick = {},
                    contentDescription = "Icon disabled",
                    enabled = false,
                ) {
                    GtCalendarMark(
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.xl),
                    )
                }
            }
            Text(
                text = "Icon clicks: $iconClicks",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )

            GtSectionHeader(title = "Dialog")
            SecondaryButton(
                text = "Open dialog",
                onClick = {
                    alerts.show(
                        title = "Remove plant?",
                        message = "This cannot be undone.",
                        buttons = listOf(
                            GtAlertButton(
                                text = "Cancel",
                                style = GtAlertButtonStyle.Cancel,
                                onClick = { dialogResult = "cancel" },
                            ),
                            GtAlertButton(
                                text = "Delete",
                                style = GtAlertButtonStyle.Destructive,
                                onClick = { dialogResult = "delete" },
                            ),
                        ),
                    )
                },
                modifier = Modifier.gtButtonWidth(),
            )
            Text(
                text = "Dialog result: $dialogResult",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )

            GtSectionHeader(title = "Date")
            Text(
                text = "Sample en ${formatPickerDate("2026-01-09", "en")}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Text(
                text = "Sample ru ${formatPickerDate("2026-01-09", "ru")}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Text(
                text = "Maximum $today",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            GtInline {
                GtChip(
                    label = "Locale en",
                    selected = languageTag == "en",
                    onClick = { languageTag = "en" },
                )
                GtChip(
                    label = "Locale ru",
                    selected = languageTag == "ru",
                    onClick = { languageTag = "ru" },
                )
            }
            GtDatePickerField(
                value = dateValue,
                onValueChange = { dateValue = it },
                placeholder = "Choose a date",
                confirmLabel = "Save",
                dismissLabel = "Cancel date",
                languageTag = languageTag,
            )
            Text(
                text = "Date value: ${dateValue ?: "none"}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )

            GtSectionHeader(title = "Image")
            GtInline(gap = Spacing.lg) {
                GtImagePickerField(
                    hasImage = false,
                    label = "Add photo",
                    sourceTitle = "Photo source",
                    cameraLabel = "Camera",
                    galleryLabel = "Gallery",
                    removeLabel = "Remove photo",
                    cancelLabel = "Cancel photo",
                    onPickRequested = { source ->
                        pickResult = when (source) {
                            GtImageSource.Camera -> "camera"
                            GtImageSource.Gallery -> "gallery"
                        }
                    },
                    onRemove = { pickResult = "removed" },
                )
                GtImagePickerField(
                    hasImage = true,
                    label = "Photo set",
                    sourceTitle = "Photo source",
                    cameraLabel = "Camera set",
                    galleryLabel = "Gallery set",
                    removeLabel = "Remove set",
                    cancelLabel = "Cancel set",
                    onPickRequested = { source ->
                        pickResult = when (source) {
                            GtImageSource.Camera -> "filled-camera"
                            GtImageSource.Gallery -> "filled-gallery"
                        }
                    },
                    onRemove = { pickResult = "removed" },
                )
            }
            Text(
                text = "Pick requested: $pickResult",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )

            GtSectionHeader(title = "Empty")
            GtEmptyState(
                title = "No plants",
                message = "Add a plant to start tracking.",
                actionLabel = "Add plant",
                onAction = {},
            )

            GtSectionHeader(title = "Skeleton")
            GtSkeleton(mode = GtSkeletonMode.List, count = 2)
            GtSkeleton(mode = GtSkeletonMode.Card, count = 1)
            GtSkeleton(mode = GtSkeletonMode.Grid, count = 3)
        }
        GtAlertDialogHost(alerts)
    }
}
