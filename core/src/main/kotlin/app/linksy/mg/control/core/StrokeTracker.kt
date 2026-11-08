package app.linksy.mg.control.core

/**
 * Remembers the platform stroke object ([S]) that a continuing drag segment must extend, and keeps
 * it consistent with the [ActionQueue]'s endpoint. A gesture the platform rejected or cancelled
 * leaves nothing to continue, so later segments start a fresh stroke.
 */
class StrokeTracker<S>(private val queue: ActionQueue) {
    private var last: S? = null

    /** The stroke to extend, or null for a fresh one. */
    fun continuation(fresh: Boolean): S? = if (fresh) null else last

    /** The gesture built from [stroke] was handed to the platform. */
    fun dispatched(stroke: S, willContinue: Boolean) {
        last = if (willContinue) stroke else null
    }

    /** dispatchGesture returned false: Android never accepted the stroke. */
    fun rejected() = lost()

    /** The platform cancelled the gesture. */
    fun cancelled() = lost()

    private fun lost() {
        last = null
        queue.strokeLost()
    }
}
