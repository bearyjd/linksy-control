package app.linksy.mg.control.core

class StrokeTracker<S>(private val queue: ActionQueue) {
    fun continuation(fresh: Boolean): S? = null
    fun dispatched(stroke: S, willContinue: Boolean) = Unit
    fun rejected() = Unit
    fun cancelled() = Unit
}
