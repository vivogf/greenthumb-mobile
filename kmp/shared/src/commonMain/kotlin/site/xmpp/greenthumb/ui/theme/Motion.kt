package site.xmpp.greenthumb.ui.theme

import androidx.compose.animation.core.Easing

/**
 * Длительности и easing из текущего RN, не из памяти changelog.
 *
 * Easing — полиномы Reanimated (`node_modules/react-native-reanimated/src/Easing.ts`),
 * не CSS-bezier: `cubic(t) = t³`, `quad(t) = t²`, `out(e) = 1 - e(1-t)`,
 * `inOut` симметричен относительно 0.5. Дефолт `withTiming` — `Easing.inOut(Easing.quad)`.
 *
 * Пружины — параметры Reanimated `springify()`, НЕ `Spring.DampingRatio` Compose
 * (там 0..1). Передавать [ListLayoutDamping] в `spring(dampingRatio = …)` нельзя.
 * `springify()` без аргументов: damping 10, mass 1, stiffness 100
 * (`Reanimated3DefaultSpringConfig`).
 *
 * Источники:
 * - список: `FadeInDown.delay(index * 40).duration(300).springify()` и
 *   `LinearTransition.springify()` / `.damping(11).stiffness(90)` в `app/(tabs)/index.tsx`
 * - скелетон: `withTiming` 800 мс туда и обратно, easing по умолчанию,
 *   opacity 0.35↔1 — `components/SkeletonPlaceholder.tsx`
 * - сердца полива: 1800 мс `Easing.out(Easing.cubic)`, задержка 0..300 мс —
 *   `components/WaterParticles.tsx`; кнопка ждёт 400 мс до мутации и ещё 800 мс
 *   держит частицы — `components/WaterButtonWithParticles.tsx`
 * - распад: `ANIM_DURATION = 1300`, частицы `Easing.out(Easing.cubic)`,
 *   затухание контента `delay + 250` и `duration * 0.75` с `Easing.in(Easing.quad)`,
 *   сдвиг по X до 450+200 мс — `components/ThanosSnap.tsx`
 * - ожидание массового полива 1150 мс и баннер успеха 2400 мс —
 *   `BULK_WATER_SNAP_MS` / `BULK_WATER_SUCCESS_BANNER_MS` в `app/(tabs)/index.tsx`.
 *   Связка «данные ждут анимацию» не переносится (Stage 4); числа остаются здесь,
 *   чтобы Stage 10 взял ту же длительность эффекта и баннера.
 *
 * `withTiming(0, 500, Easing.in)` из старого WaterFade в текущем `index.tsx` нет
 * (эффект заменён ThanosSnap). Токен 500 мс не заводится.
 */
object Motion {
    const val ListEnterMs = 300
    const val ListEnterStaggerMs = 40
    const val ListLayoutDamping = 10f
    const val ListLayoutMass = 1f
    const val ListLayoutStiffness = 100f
    const val GridLayoutDamping = 11f
    const val GridLayoutMass = 1f
    const val GridLayoutStiffness = 90f

    const val SkeletonPulseMs = 800

    const val WaterParticlesMs = 1800
    const val WaterParticleStaggerMaxMs = 300
    const val WaterPressLeadMs = 400
    const val WaterParticlesHoldMs = 800

    const val ThanosParticlesMs = 1300
    const val ThanosContentDelayMs = 250
    const val ThanosContentFadeMs = 975
    const val ThanosSweepBaseMs = 450
    const val ThanosSweepJitterMs = 200

    const val BulkWaterSnapMs = 1150
    const val BulkWaterSuccessBannerMs = 2400

    /** `Easing.out(Easing.cubic)` — частицы полива и пыль ThanosSnap. */
    val OutCubic: Easing = Easing { t ->
        val u = 1f - t
        1f - u * u * u
    }

    /** `Easing.in(Easing.quad)` — затухание контента ThanosSnap. */
    val InQuad: Easing = Easing { t -> t * t }

    /** Дефолт `withTiming` (`Easing.inOut(Easing.quad)`) — пульс скелетона. */
    val InOutQuad: Easing = Easing { t ->
        if (t < 0.5f) {
            val doubled = t * 2f
            (doubled * doubled) / 2f
        } else {
            val doubled = (1f - t) * 2f
            1f - (doubled * doubled) / 2f
        }
    }
}
