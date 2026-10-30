package site.xmpp.greenthumb.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Шкала отступов, собранная из фактических padding/gap RN (`app/`, `components/`),
 * не из догадки. На шкале только шаги, которые план фиксирует: 4/8/12/14/16/20/24.
 *
 * Откуда шаги (подсчёт по исходникам, 2026-09-25):
 * - [xxs] 4 — 9 padding + 9 gap: login, tabs `_layout`, dashboard, profile, DatePickerInput
 * - [xs] 8 — 9 padding + 18 gap: enable-notifications, welcome, dashboard, plant detail,
 *   AlertDialog, ImagePickerField
 * - [sm] 12 — 15 padding + 19 gap: login (`paddingVertical` поля), dashboard, profile,
 *   add-plant, plant detail, SkeletonPlaceholder
 * - [md] 14 — 33 padding, самый частый горизонтальный отступ экрана: login, welcome,
 *   dashboard, profile, add-plant, plant detail, AlertDialog, enable-notifications
 *   (`paddingHorizontal: 14` у полей, `login.tsx` inputStyle)
 * - [lg] 16 — 14 padding + 4 gap: welcome, dashboard (`GRID_PADDING = 16` в
 *   `app/(tabs)/index.tsx`), profile, add-plant, plant detail
 * - [xl] 20 — 25 padding + 6 gap: login, welcome, dashboard, profile, add-plant,
 *   plant detail, AlertDialog, DatePickerInput
 * - [xxl] 24 — 12 padding + 1 gap: enable-notifications, login, dashboard, profile,
 *   plant detail, HandoffKeyModal
 *
 * Вне шкалы, токеном не становятся (ближайший шаг — не повод плодить литерал в экране):
 * - 2/3/5/6 — волосяные зазоры иконка-текст (dashboard, plant detail, ImagePickerField)
 * - 10 — частый gap (26), в том числе `GRID_GAP = 10` в `app/(tabs)/index.tsx`.
 *   На шкале Spacing его нет; ближайшие токены [xs] и [sm]. Радиус 10 — это [Radii.md]
 * - 13, 15, 18, 34 — единичные (add-plant, DatePickerInput, plant detail)
 * - 32, 40, 100 — крупные отступы секций и пустых состояний, не шаг сетки
 *
 * M10 (D§3.3): new code uses only 4/8/12/16/24 ([xxs], [xs], [sm], [lg], [xxl]) and [Sizes].
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp

    /** 14: kept for existing screens only, not for new code (D§3.3). */
    val md = 14.dp
    val lg = 16.dp

    /** 20: kept for existing screens only, not for new code (D§3.3). */
    val xl = 20.dp
    val xxl = 24.dp
}

/** Layout sizes of the «Лес» design (D§3.3). */
object Sizes {
    /** Minimum touch target. */
    val touch = 48.dp

    /** Height of a bottom-sheet row. */
    val sheetRow = 56.dp

    /** Fixed icon slot in sheet rows, so labels line up with and without an icon. */
    val iconSlot = 24.dp
    val screenPadding = Spacing.lg
    val cardPadding = Spacing.lg
    val cardGap = Spacing.sm

    /** Height of the watering progress bar on plant detail. */
    val progressBar = 8.dp

    /** One likelihood dot of a diagnosis cause. */
    val likelihoodDot = 8.dp

    /** Height of the photo on the diagnosis result. */
    val diagnosisPhoto = 140.dp

    /** Photo thumbnail of a journal entry. */
    val journalThumb = 48.dp

    /** Round smart camera button of the dashboard (same size as the «+» button). */
    val smartCameraButton = 56.dp

    /** Height of the full-width photo box on Add Plant. */
    val photoBoxTall = 200.dp

    /** Width of the smart camera glow ring. */
    val glowBorder = 2.dp
}
