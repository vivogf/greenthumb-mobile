package site.xmpp.greenthumb.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Радиусы скруглений, собранные из `borderRadius` RN. Шкала плана: 8/10/12/16.
 *
 * Откуда шаги (подсчёт по `app/` и `components/`, 2026-09-25):
 * - [sm] 8 — 8 вхождений: dashboard, plant detail (мелкие бейджи и чипы)
 * - [md] 10 — 27 вхождений, основной радиус контролов: login inputStyle,
 *   welcome, enable-notifications, profile, add-plant, DatePickerInput,
 *   HandoffKeyModal, ImagePickerField, WaterButtonWithParticles
 * - [lg] 12 — 8 вхождений: карточки dashboard, add-plant, plant detail
 * - [xl] 16 — 12 вхождений: диалоги и листы (AlertDialog, login, enable-notifications,
 *   dashboard, profile, plant detail, HandoffKeyModal, ImagePickerField, SkeletonPlaceholder)
 *
 * Вне шкалы, токеном не становятся:
 * - 1/2/4/6 — пыль ThanosSnap и дефолт «кости» скелетона (`borderRadius = 6`)
 * - 14 — 5 вхождений (dashboard, profile, ImagePickerField, SkeletonPlaceholder);
 *   между [lg] и [xl], на шкале нет
 * - 19/20/24/28/32/36/40/60 — круги и пилюли (половина стороны контрола),
 *   не угол карточки. Пример: компактная кнопка полива 40×40 с `borderRadius: 20`
 *   в `components/WaterButtonWithParticles.tsx`. Пилюля = половина размера, не [Radii]
 */
object Radii {
    val sm = 8.dp
    val md = 10.dp
    val lg = 12.dp
    val xl = 16.dp
}
