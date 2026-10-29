package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtCloseMark
import site.xmpp.greenthumb.ui.components.GtFertilizeMark
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPotMark
import site.xmpp.greenthumb.ui.components.GtScissorsMark
import site.xmpp.greenthumb.ui.components.GtStepper
import site.xmpp.greenthumb.ui.components.GtSwitch
import site.xmpp.greenthumb.ui.components.GtWaterDropMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.STEPPER_DEC
import site.xmpp.greenthumb.ui.components.STEPPER_INC
import site.xmpp.greenthumb.ui.components.STEPPER_UNIT
import site.xmpp.greenthumb.ui.components.STEPPER_VALUE
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_done
import site.xmpp.greenthumb.ui.res.plantDetails_careAlwaysOn
import site.xmpp.greenthumb.ui.res.plantDetails_careDays
import site.xmpp.greenthumb.ui.res.plantDetails_careDecrease
import site.xmpp.greenthumb.ui.res.plantDetails_careHowOften
import site.xmpp.greenthumb.ui.res.plantDetails_careIncrease
import site.xmpp.greenthumb.ui.res.plantDetails_careMonths
import site.xmpp.greenthumb.ui.res.plantDetails_careOff
import site.xmpp.greenthumb.ui.res.plantDetails_careOffline
import site.xmpp.greenthumb.ui.res.plantDetails_careOn
import site.xmpp.greenthumb.ui.res.plantDetails_careRejected
import site.xmpp.greenthumb.ui.res.plantDetails_careServerError
import site.xmpp.greenthumb.ui.res.plantDetails_careSheetTitle
import site.xmpp.greenthumb.ui.res.plantDetails_careWatering
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_fertilizing
import site.xmpp.greenthumb.ui.res.plantDetails_lastTime
import site.xmpp.greenthumb.ui.res.plantDetails_lastWatered
import site.xmpp.greenthumb.ui.res.plantDetails_notSpecified
import site.xmpp.greenthumb.ui.res.plantDetails_pruning
import site.xmpp.greenthumb.ui.res.plantDetails_repotting
import site.xmpp.greenthumb.ui.res.plantDetails_save
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing

/** Test tags of [PlantCareSettingsSheet] (jvm tests, Maestro, device dumps). */
internal object CareTags {
    const val TITLE = "care_title"
    const val CLOSE = "care_close"
    const val SAVE = "care_save"
    const val ERROR = "care_error"
    const val DEC = STEPPER_DEC
    const val INC = STEPPER_INC
    const val VALUE = STEPPER_VALUE
    const val UNIT = STEPPER_UNIT

    fun card(type: CareType): String = "care_card_" + type.name.lowercase()

    fun stepper(type: CareType): String = "care_" + type.name.lowercase()

    fun switch(type: CareType): String = "care_" + type.name.lowercase() + "_switch"
}

/** Unit word for [count] of [type]: «день/дня/дней» or «месяц/месяца/месяцев». */
@Composable
internal fun careUnitText(type: CareType, count: Int): String =
    pluralStringResource(if (type.inMonths) Res.plurals.plantDetails_careMonths else Res.plurals.plantDetails_careDays, count, count)

@Composable
private fun careTitle(type: CareType): String = stringResource(
    when (type) {
        CareType.Water -> Res.string.plantDetails_careWatering
        CareType.Fertilize -> Res.string.plantDetails_fertilizing
        CareType.Repot -> Res.string.plantDetails_repotting
        CareType.Prune -> Res.string.plantDetails_pruning
    },
)

@Composable
private fun CareMark(type: CareType, tint: Color) {
    val modifier = Modifier.size(Sizes.iconSlot)
    when (type) {
        CareType.Water -> GtWaterDropMark(tint = tint, modifier = modifier)
        CareType.Fertilize -> GtFertilizeMark(tint = tint, modifier = modifier)
        CareType.Repot -> GtPotMark(tint = tint, modifier = modifier)
        CareType.Prune -> GtScissorsMark(tint = tint, modifier = modifier)
    }
}

/**
 * «Уход за растением», variant A (M10 D§1 item 7a): one card per care type. Watering is always on; the other
 * three have a switch, and a switched-on type shows a −/+ stepper with the unit word. «Сохранить» at the bottom
 * saves; while the save runs the button is disabled. [error] is the last failed save: the sheet stays open with
 * the edited values and says why. ✕, system Back and the scrim cancel without saving.
 */
@Composable
internal fun PlantCareSettingsSheet(
    form: CareSettingsForm,
    plantName: String,
    catalog: CatalogRepository?,
    locale: String,
    saving: Boolean,
    error: CareSaveError?,
    onFormChange: (CareSettingsForm) -> Unit,
    onChooseSpecies: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GtModal(onDismissRequest = onCancel) {
        val modalFocus = LocalFocusManager.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.plantDetails_careSheetTitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(CareTags.TITLE),
                )
                Text(
                    text = plantName,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GtIconButton(
                onClick = {
                    modalFocus.clearFocus()
                    onCancel()
                },
                contentDescription = stringResource(Res.string.plantDetails_cancel),
                selectable = false,
                modifier = Modifier.testTag(CareTags.CLOSE),
            ) {
                GtCloseMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Sizes.iconSlot))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.xs, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            GtCard(modifier = Modifier.fillMaxWidth()) {
                SettingsSpeciesRow(
                    form = form,
                    catalog = catalog,
                    locale = locale,
                    onChoose = {
                        modalFocus.clearFocus()
                        onChooseSpecies()
                    },
                    onClear = { onFormChange(form.withoutSpecies()) },
                )
            }
            CareType.entries.forEach { type ->
                CareTypeCard(type = type, form = form, locale = locale, onFormChange = onFormChange)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (error != null) {
                Text(
                    text = stringResource(
                        when (error) {
                            CareSaveError.Rejected -> Res.string.plantDetails_careRejected
                            CareSaveError.Server -> Res.string.plantDetails_careServerError
                            CareSaveError.Offline -> Res.string.plantDetails_careOffline
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.error,
                    modifier = Modifier.testTag(CareTags.ERROR),
                )
            }
            PrimaryButton(
                text = stringResource(Res.string.plantDetails_save),
                onClick = {
                    modalFocus.clearFocus()
                    onSave()
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.sheetRow)
                    .testTag(CareTags.SAVE),
            )
        }
    }
}

@Composable
private fun CareTypeCard(
    type: CareType,
    form: CareSettingsForm,
    locale: String,
    onFormChange: (CareSettingsForm) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val on = form.isOn(type)
    val title = careTitle(type)
    GtCard(modifier = Modifier.fillMaxWidth().testTag(CareTags.card(type))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CareMark(type, if (on) scheme.secondary else scheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Text(
                    text = stringResource(
                        when {
                            !type.switchable -> Res.string.plantDetails_careAlwaysOn
                            on -> Res.string.plantDetails_careOn
                            else -> Res.string.plantDetails_careOff
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (type.switchable) {
                GtSwitch(
                    checked = on,
                    onCheckedChange = { onFormChange(form.setOn(type, it)) },
                    contentDescription = title,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag(CareTags.switch(type)),
                )
            }
        }
        if (on) {
            Column(
                modifier = Modifier.padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = stringResource(Res.string.plantDetails_careHowOften),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                val value = form.value(type)
                GtStepper(
                    value = value,
                    range = type.range,
                    onValueChange = { onFormChange(form.step(type, it - value)) },
                    unit = careUnitText(type, value),
                    decreaseLabel = stringResource(Res.string.plantDetails_careDecrease, title),
                    increaseLabel = stringResource(Res.string.plantDetails_careIncrease, title),
                    testTagPrefix = CareTags.stepper(type),
                )
                SettingsDateField(
                    label = stringResource(
                        if (type == CareType.Water) Res.string.plantDetails_lastWatered else Res.string.plantDetails_lastTime,
                    ),
                    value = form.date(type),
                    onValueChange = { onFormChange(form.withDate(type, it)) },
                    placeholder = stringResource(Res.string.plantDetails_notSpecified),
                    doneText = stringResource(Res.string.common_done),
                    cancelText = stringResource(Res.string.common_cancel),
                    locale = locale,
                )
            }
        }
    }
}
