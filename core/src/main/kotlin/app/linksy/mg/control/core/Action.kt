package app.linksy.mg.control.core

/** A point in display pixels. */
data class Point(val x: Float, val y: Float)

/** The display the fractions are scaled by. Passed per call so rotation is always current. */
data class Display(val width: Int, val height: Int) {
    val valid: Boolean get() = width > 0 && height > 0
}

/** What the phone should do. The Android glue turns these into gestures, global actions, text edits. */
sealed interface Action {
    data class Tap(val at: Point) : Action
    data class LongPress(val at: Point, val durationMs: Long) : Action

    /**
     * One segment of a finger stroke. [continuing] = the finger stays down after this segment
     * (willContinue); the next Drag starts at this one's last point. [gestureId] names the logical
     * gesture (button-down to button-up) it belongs to; [NO_GESTURE] for legacy or synthesized drags.
     */
    data class Drag(
        val path: List<Point>,
        val durationMs: Long,
        val continuing: Boolean,
        val gestureId: Long = NO_GESTURE,
    ) : Action {
        companion object {
            const val NO_GESTURE = 0L
        }
    }

    /** A scroll: a quick two-point swipe. */
    data class Swipe(val from: Point, val to: Point, val durationMs: Long) : Action

    data object Back : Action
    data object Home : Action
    data object PerformImeAction : Action
    data object Backspace : Action

    /** Insert [text] at the selection of the focused field. */
    data class SetText(val text: String) : Action
}

enum class Reject { MALFORMED, TOO_LARGE, INVALID }

/** [rejected] is set for bad input; an unknown or irrelevant message is just an empty, unrejected result. */
data class MapResult(val actions: List<Action> = emptyList(), val rejected: Reject? = null)
