package site.xmpp.greenthumb.ui.screens.addplant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier

/**
 * What the user has put into one Add Plant form: the fields, the cover bytes that «Узнать вид по фото»
 * sends, and whether «Ещё параметры» is open.
 */
public class AddPlantFormState(fields: AddPlantFields, coverBytes: ByteArray?) {
    public var fields: AddPlantFields by mutableStateOf(fields)
    public var coverBytes: ByteArray? by mutableStateOf(coverBytes)
    public var moreOpen: Boolean by mutableStateOf(false)

    public companion object {
        /** A new form, prefilled with [draft] when the smart camera handed one over. */
        public fun create(draft: AddPlantDraft?, today: String, language: String): AddPlantFormState =
            AddPlantFormState(
                fields = draft?.let { AddPlantDraft.initialFields(it, today, language) } ?: AddPlantFields(lastWateredDate = today),
                coverBytes = draft?.photoJpeg,
            )
    }
}

/**
 * Keeps [AddPlantFormState] in the ViewModel store of the navigation entry. The store outlives an
 * Activity recreation (font scale, night mode, locale, window size) and is cleared when the entry
 * leaves the back stack, so the photo bytes never go into the saved-state Bundle.
 */
internal class AddPlantFormHolder : ViewModel() {
    var state: AddPlantFormState? = null

    override fun onCleared() {
        state = null
    }
}

private val holderFactory = viewModelFactory { initializer { AddPlantFormHolder() } }

/** The form of the entry [owner]; [create] runs only the first time this entry shows the form. */
@Composable
public fun rememberAddPlantForm(owner: ViewModelStoreOwner, create: () -> AddPlantFormState): AddPlantFormState {
    val holder = remember(owner) { ViewModelProvider.create(owner.viewModelStore, holderFactory)[AddPlantFormHolder::class] }
    return remember(holder) { holder.state ?: create().also { holder.state = it } }
}

/**
 * Add Plant as a navigation destination: the form state belongs to [owner] (the back stack entry).
 * [takeDraft] is asked once per entry, so a recreated screen keeps the camera draft it already took.
 */
@Composable
public fun AddPlantRoute(
    owner: ViewModelStoreOwner,
    userId: String,
    opener: PlantRepositoryOpener,
    onBack: () -> Unit,
    identifier: SpeciesIdentifier?,
    takeDraft: () -> AddPlantDraft? = { null },
) {
    val language = LocalAppLocale.current
    val form = rememberAddPlantForm(owner) { AddPlantFormState.create(takeDraft(), pickerToday().toString(), language) }
    AddPlantScreen(userId = userId, opener = opener, onBack = onBack, identifier = identifier, form = form)
}
