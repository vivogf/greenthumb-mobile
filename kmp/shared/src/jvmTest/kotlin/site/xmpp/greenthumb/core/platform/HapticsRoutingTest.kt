package site.xmpp.greenthumb.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VAL-ANIM-004: маршрутизация тактильных событий — семантические методы
 * [Haptics] (светлый/средний/selection/успех/ошибка) доходят до платформенного
 * исполнителя ровно тем событием, которое звал RN в этих точках
 * (`Haptics.impactAsync/selectionAsync/notificationAsync`). Плюс JVM-дефолт:
 * платформенный исполнитель харнесса — no-op и не бросает.
 */
class HapticsRoutingTest {

    /** Injected recorder — доказывает, что роутер отдаёт событие наружу. */
    private class Recorder : HapticSink {
        val events = mutableListOf<HapticEvent>()
        override fun perform(event: HapticEvent) {
            events += event
        }
    }

    @Test
    fun light_routes_light() {
        val recorder = Recorder()
        val original = Haptics.sink
        try {
            Haptics.sink = recorder
            Haptics.light()
        } finally {
            Haptics.sink = original
        }
        assertEquals(listOf(HapticEvent.Light), recorder.events)
    }

    @Test
    fun medium_routes_medium() {
        val recorder = Recorder()
        val original = Haptics.sink
        try {
            Haptics.sink = recorder
            Haptics.medium()
        } finally {
            Haptics.sink = original
        }
        assertEquals(listOf(HapticEvent.Medium), recorder.events)
    }

    @Test
    fun selection_routes_selection() {
        val recorder = Recorder()
        val original = Haptics.sink
        try {
            Haptics.sink = recorder
            Haptics.selection()
        } finally {
            Haptics.sink = original
        }
        assertEquals(listOf(HapticEvent.Selection), recorder.events)
    }

    @Test
    fun success_routes_success() {
        val recorder = Recorder()
        val original = Haptics.sink
        try {
            Haptics.sink = recorder
            Haptics.success()
        } finally {
            Haptics.sink = original
        }
        assertEquals(listOf(HapticEvent.Success), recorder.events)
    }

    @Test
    fun error_routes_error() {
        val recorder = Recorder()
        val original = Haptics.sink
        try {
            Haptics.sink = recorder
            Haptics.error()
        } finally {
            Haptics.sink = original
        }
        assertEquals(listOf(HapticEvent.Error), recorder.events)
    }

    @Test
    fun jvm_default_sink_is_noop_and_does_not_throw() {
        val original = Haptics.sink
        try {
            // Дефолтный исполнитель харнесса (platformHapticSink) — no-op:
            // экран и действие без вибромотора не ломаются.
            Haptics.sink = platformHapticSink()
            Haptics.light()
            Haptics.medium()
            Haptics.selection()
            Haptics.success()
            Haptics.error()
            assertTrue(true)
        } finally {
            Haptics.sink = original
        }
    }
}
