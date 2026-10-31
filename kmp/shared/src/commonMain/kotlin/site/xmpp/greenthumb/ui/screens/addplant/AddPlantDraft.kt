package site.xmpp.greenthumb.ui.screens.addplant

import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate

/**
 * What the smart camera hands to Add Plant: the chosen candidate and the identified photo as the cover
 * (3:4 for a shot, the picked aspect for a gallery photo; long side 800).
 */
public class AddPlantDraft(
    public val candidate: IdentifyCandidate,
    public val photoJpeg: ByteArray,
) {
    public companion object {
        /** The form as Add Plant opens with a draft: the species applied and the photo as the cover. */
        public fun initialFields(draft: AddPlantDraft, lastWateredDate: String, language: String): AddPlantFields =
            AddPlantForm.applyCandidate(AddPlantFields(lastWateredDate = lastWateredDate), draft.candidate, language)
                .copy(photoUrl = photoDataUri(draft.photoJpeg))
    }
}

/**
 * One-slot store for [AddPlantDraft]: a photo does not fit in a navigation argument, so the route
 * carries a token. A draft can be taken once; a new draft replaces the old one.
 */
public class AddPlantDraftStore {
    private var token: String? = null
    private var draft: AddPlantDraft? = null
    private var counter = 0

    public fun put(value: AddPlantDraft): String {
        counter++
        val next = "d$counter"
        token = next
        draft = value
        return next
    }

    public fun take(key: String): AddPlantDraft? {
        if (key != token) return null
        val value = draft
        token = null
        draft = null
        return value
    }
}
