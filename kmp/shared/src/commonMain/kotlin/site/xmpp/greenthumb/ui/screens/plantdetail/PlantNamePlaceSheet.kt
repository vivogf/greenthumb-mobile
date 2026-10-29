package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.components.GtCheckMark
import site.xmpp.greenthumb.ui.components.GtChip
import site.xmpp.greenthumb.ui.components.GtCloseMark
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPencilMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_nameLabel
import site.xmpp.greenthumb.ui.res.plantDetails_namePlaceTitle
import site.xmpp.greenthumb.ui.res.plantDetails_placeCustom
import site.xmpp.greenthumb.ui.res.plantDetails_placeCustomHint
import site.xmpp.greenthumb.ui.res.plantDetails_placeLabel
import site.xmpp.greenthumb.ui.res.plantDetails_save
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing

/** Test tags of [PlantNamePlaceSheet] (jvm tests, Maestro, device dumps). */
internal object NamePlaceTags {
    const val TITLE = "namePlace_title"
    const val CANCEL = "namePlace_cancel"
    const val CONFIRM = "namePlace_confirm"
    const val NAME = "namePlace_name"
    const val CHIP_PREFIX = "namePlace_chip_"
    const val CUSTOM_CHIP = "namePlace_chip_custom"
    const val CUSTOM_FIELD = "namePlace_custom"
    const val SAVE = "namePlace_save"
}

/**
 * «Название и место» (items 5-6, variant A): ✕ cancels, ✓ and the big «Сохранить» save both fields
 * with one PATCH. System Back and a tap on the scrim cancel too ([GtModal] routes them to [onCancel]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlantNamePlaceSheet(
    form: NamePlaceForm,
    onFormChange: (NamePlaceForm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val cancelText = stringResource(Res.string.plantDetails_cancel)
    val saveText = stringResource(Res.string.plantDetails_save)
    GtModal(onDismissRequest = onCancel) {
        // The dialog has its own focus owner: a field focused here must be cleared from inside.
        val modalFocus = LocalFocusManager.current
        val cancel = {
            modalFocus.clearFocus()
            onCancel()
        }
        val save = {
            if (form.isValid) {
                modalFocus.clearFocus()
                onSave()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            GtIconButton(
                onClick = cancel,
                contentDescription = cancelText,
                selectable = false,
                modifier = Modifier.testTag(NamePlaceTags.CANCEL),
            ) {
                GtCloseMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Sizes.iconSlot))
            }
            Text(
                text = stringResource(Res.string.plantDetails_namePlaceTitle),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(NamePlaceTags.TITLE),
            )
            GtIconButton(
                onClick = save,
                contentDescription = saveText,
                enabled = form.isValid,
                selectable = false,
                modifier = Modifier.testTag(NamePlaceTags.CONFIRM),
            ) {
                GtCheckMark(
                    tint = if (form.isValid) scheme.secondary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.iconSlot),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FieldLabel(stringResource(Res.string.plantDetails_nameLabel))
                GtInputField(
                    value = form.name,
                    onValueChange = { onFormChange(form.withName(it)) },
                    modifier = Modifier.testTag(NamePlaceTags.NAME),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { modalFocus.clearFocus() }),
                )
            }

            PlaceChooser(form = form, onFormChange = onFormChange, onDone = { modalFocus.clearFocus() })

            PrimaryButton(
                text = saveText,
                onClick = save,
                enabled = form.isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.sheetRow)
                    .testTag(NamePlaceTags.SAVE),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceChooser(
    form: NamePlaceForm,
    onFormChange: (NamePlaceForm) -> Unit,
    onDone: () -> Unit,
) {
    // Set only by a tap on «Своё…», so a sheet that opens with a custom place does not pop the keyboard.
    var focusCustom by remember { mutableStateOf(false) }
    val customRequester = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FieldLabel(stringResource(Res.string.plantDetails_placeLabel))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            form.labels.forEachIndexed { index, label ->
                val selected = form.preset == index
                GtChip(
                    label = label,
                    selected = selected,
                    onClick = {
                        onDone()
                        onFormChange(form.selectPreset(index))
                    },
                    modifier = Modifier
                        .heightIn(min = Sizes.touch)
                        .testTag(NamePlaceTags.CHIP_PREFIX + index),
                    leading = if (selected) { tint -> GtCheckMark(tint, Modifier.size(Spacing.xl)) } else null,
                )
            }
            GtChip(
                label = stringResource(Res.string.plantDetails_placeCustom),
                selected = form.custom,
                onClick = {
                    val next = form.selectCustom()
                    focusCustom = next.custom
                    if (!next.custom) onDone()
                    onFormChange(next)
                },
                modifier = Modifier
                    .heightIn(min = Sizes.touch)
                    .testTag(NamePlaceTags.CUSTOM_CHIP),
                leading = { tint ->
                    if (form.custom) GtCheckMark(tint, Modifier.size(Spacing.xl)) else GtPencilMark(tint, Modifier.size(Spacing.xl))
                },
            )
        }
        if (form.custom) {
            GtInputField(
                value = form.customText,
                onValueChange = { onFormChange(form.withCustomText(it)) },
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .focusRequester(customRequester)
                    .testTag(NamePlaceTags.CUSTOM_FIELD),
                placeholder = stringResource(Res.string.plantDetails_placeCustomHint),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                leadingIcon = {
                    GtPencilMark(tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(Spacing.xl))
                },
            )
            LaunchedEffect(focusCustom) {
                if (focusCustom) {
                    customRequester.requestFocus()
                    focusCustom = false
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
