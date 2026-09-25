package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Замена `components/DatePickerInput.tsx`.
 *
 * Верхняя граница — сегодня, будущий день не выбирается. Подпись — по
 * [languageTag] ([formatPickerDate]), не хардкод ru-RU.
 * Отмена не пишет значение. Подтверждение пишет `YYYY-MM-DD`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GtDatePickerField(
    value: String?,
    onValueChange: (String) -> Unit,
    placeholder: String,
    confirmLabel: String,
    dismissLabel: String,
    languageTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val today = remember { pickerToday() }
    val selectable = remember(today) { OnOrBeforeToday(today) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = pickerMillis(today),
        initialDisplayedMonthMillis = pickerMillis(today),
        yearRange = DatePickerDefaults.YearRange.first..today.year,
        selectableDates = selectable,
    )
    var open by remember { mutableStateOf(false) }
    val normalized = normalizePickerDate(value)
    val shown = normalized?.let { formatPickerDate(it, languageTag) } ?: placeholder
    val shape = RoundedCornerShape(Radii.md)
    LaunchedEffect(open, normalized, today) {
        if (!open) return@LaunchedEffect
        val initial = normalized
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it <= today }
            ?: today
        state.selectedDateMillis = pickerMillis(initial)
        state.displayedMonthMillis = pickerMillis(initial)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface, shape)
            .borderHairline(scheme.outline, shape)
            .clickable(enabled = enabled, onClick = { if (enabled) open = true }, role = Role.Button)
            .semantics { contentDescription = "Date field $shown" }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GtCalendarMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.xl))
        Text(
            text = shown,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (normalized == null) scheme.onSurfaceVariant else scheme.onSurface,
        )
        GtChevronMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
    }

    if (open) {
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                val selected = state.selectedDateMillis
                val canConfirm = selected != null && isSelectablePickerDate(selected, today)
                TextButton(
                    onClick = {
                        val millis = state.selectedDateMillis
                        if (millis != null && isSelectablePickerDate(millis, today)) {
                            onValueChange(dateFromPickerMillis(millis).toString())
                        }
                        open = false
                    },
                    enabled = canConfirm,
                ) {
                    Text(text = confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(text = dismissLabel)
                }
            },
        ) {
            DatePicker(
                state = state,
                title = { Text(text = "Maximum $today") },
                showModeToggle = false,
            )
        }
    }
}

private class OnOrBeforeToday(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        isSelectablePickerDate(utcTimeMillis, today)

    override fun isSelectableYear(year: Int): Boolean =
        isSelectablePickerYear(year, today)
}
