package app.linksy.mg.control.core

class GestureMapper {
    companion object { const val MAX_PATH_POINTS = 64 }
    fun handle(json: String, display: Display, nowMs: Long): MapResult = MapResult()
    fun release(display: Display, nowMs: Long): List<Action> = emptyList()
}
