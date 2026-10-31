package site.xmpp.greenthumb.core.platform

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Android-actual: тактильные события через системный Vibrator.
 *
 * Соответствие эффектам expo-haptics (последний использует predefined-эффекты
 * Q+ с waveform-фолбэком ниже): Light/Selection → EFFECT_TICK, Medium/Success →
 * EFFECT_CLICK, Error → EFFECT_HEAVY_CLICK. Ниже API 29 predefined-эффектов нет
 * (minSdk 24) — короткие waveform-эквиваленты.
 *
 * Отсутствие вибратора (`hasVibrator() == false`, сервис недоступен, отказ
 * API) — тихий no-op: действие и экран не ломаются (VAL-ANIM-004).
 *
 * Системная настройка «Вибрация при касании» выключена — тоже no-op: прямой
 * Vibrator её сам не проверяет (в отличие от View.performHapticFeedback).
 */
public actual fun platformHapticSink(): HapticSink = AndroidHapticSink

private object AndroidHapticSink : HapticSink {
    override fun perform(event: HapticEvent) {
        val context: Context = appContextOrNull() ?: return
        if (!touchFeedbackEnabled(context)) return
        val vibrator = vibratorOrNull(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching { vibrate(vibrator, event) }
    }

    // Deprecated on API 33 in favour of USAGE_TOUCH (used below), but older systems only know this switch.
    @Suppress("DEPRECATION")
    private fun touchFeedbackEnabled(context: Context): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(true)

    private fun vibratorOrNull(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java) ?: return null
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(vibrator: Vibrator, event: HapticEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = when (event) {
                HapticEvent.Light, HapticEvent.Selection -> VibrationEffect.EFFECT_TICK
                HapticEvent.Medium, HapticEvent.Success -> VibrationEffect.EFFECT_CLICK
                HapticEvent.Error -> VibrationEffect.EFFECT_HEAVY_CLICK
            }
            val predefined = VibrationEffect.createPredefined(effect)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Touch usage: the system also applies its touch-vibration intensity to it.
                vibrator.vibrate(predefined, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                vibrator.vibrate(predefined)
            }
        } else {
            val milliseconds = when (event) {
                HapticEvent.Light, HapticEvent.Selection -> 10L
                HapticEvent.Medium, HapticEvent.Success -> 20L
                HapticEvent.Error -> 30L
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, milliseconds), -1))
        }
    }
}
