package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePickerState
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
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.datePicker_fieldDescription
import site.xmpp.greenthumb.ui.res.datePicker_maximum
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Замена `components/DatePickerInput.tsx`.
 *
 * Верхняя граница — сегодня, будущий день не выбирается. Подпись и календарь
 * (месяцы, дни недели) — по [languageTag] ([formatPickerDate], [calendarLocaleOf]),
 * не хардкод ru-RU и не язык телефона.
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
    /** Ошибка валидации под полем (рамка error, `add-plant.tsx` errorStyle). */
    errorText: String? = null,
    /** True opens the picker as if the field were tapped; [onOpenRequestHandled] then resets it. */
    openRequested: Boolean = false,
    onOpenRequestHandled: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val normalized = normalizePickerDate(value)
    val shown = normalized?.let { formatPickerDate(it, languageTag) } ?: placeholder
    val fieldDescription = stringResource(Res.string.datePicker_fieldDescription, shown)
    val shape = RoundedCornerShape(Radii.md)
    LaunchedEffect(openRequested) {
        if (!openRequested) return@LaunchedEffect
        if (enabled) open = true
        onOpenRequestHandled()
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface, shape)
                .borderHairline(if (errorText == null) scheme.outline else scheme.error, shape)
                .clickable(enabled = enabled, onClick = { if (enabled) open = true }, role = Role.Button)
                .semantics { contentDescription = fieldDescription }
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
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
    }

    if (open) {
        GtDatePickerDialog(
            initialDate = normalized,
            onConfirm = { date ->
                open = false
                onValueChange(date)
            },
            onDismiss = { open = false },
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel,
            languageTag = languageTag,
        )
    }
}

/**
 * The calendar dialog of [GtDatePickerField] without the field. Today is the upper bound and the default.
 * [title] replaces the "Maximum …" line. Confirm returns `YYYY-MM-DD`; dismiss returns nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GtDatePickerDialog(
    initialDate: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String,
    dismissLabel: String,
    languageTag: String,
    title: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val today = remember { pickerToday() }
    val selectable = remember(today) { OnOrBeforeToday(today) }
    // rememberDatePickerState has no locale: its calendar would use the phone language.
    val calendarLocale = remember(languageTag) { calendarLocaleOf(languageTag) }
    val state = remember(calendarLocale, selectable) {
        val initial = normalizePickerDate(initialDate)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it <= today }
            ?: today
        DatePickerState(
            locale = calendarLocale,
            initialSelectedDateMillis = pickerMillis(initial),
            initialDisplayedMonthMillis = pickerMillis(initial),
            yearRange = DatePickerDefaults.YearRange.first..today.year,
            selectableDates = selectable,
        )
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val selected = state.selectedDateMillis
            val canConfirm = selected != null && isSelectablePickerDate(selected, today)
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null && isSelectablePickerDate(millis, today)) {
                        onConfirm(dateFromPickerMillis(millis).toString())
                    } else {
                        onDismiss()
                    }
                },
                enabled = canConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.tertiary),
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
            ) {
                Text(text = dismissLabel)
            }
        },
    ) {
        val heading = title
            ?: stringResource(Res.string.datePicker_maximum, formatPickerDate(today.toString(), languageTag))
        val dateFormatter = remember { DatePickerDefaults.dateFormatter() }
        DatePicker(
            state = state,
            dateFormatter = dateFormatter,
            // A custom title gets no slot padding from Material3; these are the values of its default title.
            title = {
                Text(text = heading, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp))
            },
            // The default headline formats the date in the phone language; this one uses the app language.
            headline = {
                val millis = state.selectedDateMillis
                val full = dateFormatter.formatDate(millis, calendarLocale, forContentDescription = true)
                Text(
                    text = dateFormatter.formatDate(millis, calendarLocale).orEmpty(),
                    modifier = Modifier
                        .padding(start = 24.dp, end = 12.dp, bottom = 12.dp)
                        .semantics { if (full != null) contentDescription = full },
                    maxLines = 1,
                )
            },
            showModeToggle = false,
        )
    }
}
private class OnOrBeforeToday(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        isSelectablePickerDate(utcTimeMillis, today)

    override fun isSelectableYear(year: Int): Boolean =
        isSelectablePickerYear(year, today)
}
