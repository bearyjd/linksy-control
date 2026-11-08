package app.linksy.mg.control.core

class ControlEngine(private val clock: () -> Long, private val gate: SessionGate = SessionGate(clock)) {
    private val mapper = GestureMapper()
    fun begin() {}
    fun end(display: Display): List<Action> = emptyList()
    fun tick(display: Display): List<Action> = emptyList()
    fun inject(json: String, display: Display): MapResult = MapResult()
}
