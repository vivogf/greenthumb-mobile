package site.xmpp.greenthumb.ui.screens.diagnosis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.PHOTO_CAPTION_MAX_LENGTH
import site.xmpp.greenthumb.ui.theme.GreenThumbExtendedColors

/**
 * VAL-CARD-013/014: the journal caption written by «Сохранить в журнал растения», and the
 * likelihood dots and colours of the result screen.
 */
class DiagnosisJournalTest {
    private val ru = mapOf(
        Likelihood.High to "Скорее всего",
        Likelihood.Medium to "Возможно",
        Likelihood.Low to "Маловероятно",
    )
    private val en = mapOf(
        Likelihood.High to "Most likely",
        Likelihood.Medium to "Possible",
        Likelihood.Low to "Unlikely",
    )

    private fun cause(title: String, likelihood: Likelihood) = DiagnosisCause(title, likelihood, "why", "what")

    @Test
    fun one_cause_in_russian() {
        assertEquals(
            "Что с растением?: Слишком много воды (Скорее всего)",
            DiagnosisJournal.caption("Что с растением?", listOf(cause("Слишком много воды", Likelihood.High)), ru::getValue),
        )
    }

    @Test
    fun two_causes_in_russian() {
        assertEquals(
            "Что с растением?: Слишком много воды (Скорее всего), Мало света (Возможно)",
            DiagnosisJournal.caption(
                "Что с растением?",
                listOf(cause("Слишком много воды", Likelihood.High), cause("Мало света", Likelihood.Medium)),
                ru::getValue,
            ),
        )
    }

    @Test
    fun three_causes_in_english() {
        assertEquals(
            "Plant check: Overwatering (Most likely), Low light (Possible), Pests (Unlikely)",
            DiagnosisJournal.caption(
                "Plant check",
                listOf(
                    cause("Overwatering", Likelihood.High),
                    cause("Low light", Likelihood.Medium),
                    cause("Pests", Likelihood.Low),
                ),
                en::getValue,
            ),
        )
    }

    @Test
    fun line_breaks_and_runs_of_spaces_in_titles_become_one_space() {
        assertEquals(
            "Plant check: Root rot (Unlikely)",
            DiagnosisJournal.caption("Plant check", listOf(cause("  Root\n  rot ", Likelihood.Low)), en::getValue),
        )
    }

    @Test
    fun a_long_caption_is_cut_to_the_server_limit_with_an_ellipsis() {
        val causes = listOf(
            cause("А".repeat(200), Likelihood.High),
            cause("Б".repeat(200), Likelihood.Medium),
            cause("В".repeat(200), Likelihood.Low),
        )
        val caption = DiagnosisJournal.caption("Что с растением?", causes, ru::getValue)
        assertEquals(300, PHOTO_CAPTION_MAX_LENGTH)
        assertEquals(PHOTO_CAPTION_MAX_LENGTH, caption.length)
        assertTrue(caption.endsWith("…"), caption)
        assertTrue(caption.startsWith("Что с растением?: ААА"), caption)
    }

    @Test
    fun the_cut_never_splits_a_surrogate_pair() {
        val prefix = "P"
        // "P: " is 3 units; 295 letters put an emoji's high surrogate at index 298, the last kept unit.
        val title = "a".repeat(295) + "\uD83C\uDF31" + "b".repeat(20)
        val caption = DiagnosisJournal.caption(prefix, listOf(cause(title, Likelihood.High)), en::getValue)
        assertTrue(caption.length <= PHOTO_CAPTION_MAX_LENGTH)
        assertTrue(caption.endsWith("…"))
        assertTrue(!caption.dropLast(1).last().isHighSurrogate(), "no half emoji before the ellipsis")
    }

    @Test
    fun a_short_caption_is_kept_whole() {
        val caption = DiagnosisJournal.caption("Plant check", listOf(cause("x".repeat(250), Likelihood.High)), en::getValue)
        assertTrue(caption.length <= PHOTO_CAPTION_MAX_LENGTH)
        assertTrue(!caption.endsWith("…"))
    }

    @Test
    fun dots_are_three_two_one() {
        assertEquals(3, likelihoodDots(Likelihood.High))
        assertEquals(2, likelihoodDots(Likelihood.Medium))
        assertEquals(1, likelihoodDots(Likelihood.Low))
    }

    @Test
    fun colours_come_from_the_cause_tokens_in_both_themes() {
        for (colors in listOf(GreenThumbExtendedColors.dark, GreenThumbExtendedColors.light)) {
            assertEquals(colors.causeHigh, likelihoodColor(Likelihood.High, colors))
            assertEquals(colors.amber, likelihoodColor(Likelihood.Medium, colors))
            assertEquals(colors.causeLow, likelihoodColor(Likelihood.Low, colors))
        }
    }
}
