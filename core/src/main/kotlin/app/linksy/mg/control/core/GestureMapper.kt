package app.linksy.mg.control.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns Linksy `ControlMsg` JSON (`{"type":"pointer",...}`) into abstract [Action]s.
 *
 * Pure Kotlin, no android.*. Keeps one small state machine for the left button (tap / long press /
 * drag). Not thread-safe: call from one thread. Never throws on bad input; see [MapResult].
 */
class GestureMapper {
    companion object {
        const val MAX_MESSAGE_CHARS = 16_384
        const val MAX_TEXT_BYTES = 1024
        const val MAX_PATH_POINTS = 64
        const val TAP_MAX_MS = 300L
        const val LONG_PRESS_MIN_MS = 600L
        const val LONG_PRESS_MAX_MS = 5_000L
        const val SLOP_PX = 10f
        const val SEGMENT_MS = 50L
        const val MAX_SEGMENT_MS = 5_000L
        const val SWIPE_MS = 200L
        const val WHEEL_STEP = 0.12f // fraction of the screen per wheel click
        const val MAX_SWIPE = 0.4f // fraction of the screen, either direction
        const val PAGE_SWIPE = 0.4f

        private const val KEY_ESCAPE = 0xff1bL
        private const val KEY_ENTER = 0xff0dL
        private const val KEY_KP_ENTER = 0xff8dL
        private const val KEY_BACKSPACE = 0xff08L
        private const val KEY_PAGE_UP = 0xff55L
        private const val KEY_PAGE_DOWN = 0xff56L
        private const val KEY_SUPER_L = 0xffebL
        private const val KEY_SUPER_R = 0xffecL
        private const val KEYSYM_UNICODE_BASE = 0x01000000L
        private const val MAX_KEYSYM = 0xFFFFFFFFL
        private const val MAX_CODE_POINT = 0x10FFFFL
    }

    private enum class Phase { PRESSED, DRAGGING }

    /** Positions are fractions (0..1) so a rotation between messages stays correct. */
    private data class FPoint(val x: Double, val y: Double)

    private data class Gesture(
        val phase: Phase,
        val down: FPoint,
        val downMs: Long,
        val lastEmitted: FPoint,
        val lastEmitMs: Long,
        val pending: List<FPoint>,
    )

    private var lastPointer: FPoint? = null
    private var gesture: Gesture? = null

    fun handle(json: String, display: Display, nowMs: Long): MapResult {
        if (json.length > MAX_MESSAGE_CHARS) return MapResult(rejected = Reject.TOO_LARGE)
        if (!display.valid) return MapResult(rejected = Reject.INVALID)
        val obj = parse(json) ?: return MapResult(rejected = Reject.MALFORMED)
        val type = obj.string("type") ?: return MapResult(rejected = Reject.MALFORMED)
        return when (type) {
            "pointer" -> pointer(obj, display, nowMs)
            "button" -> button(obj, display, nowMs)
            "scroll" -> scroll(obj, display)
            "key" -> key(obj, display)
            "text" -> text(obj)
            else -> MapResult() // pointer_by, term_*, and anything newer: not ours
        }
    }

    /** Ends any in-progress gesture (session over). Later button-up events do nothing. */
    fun release(display: Display, nowMs: Long): List<Action> {
        val g = gesture ?: return emptyList()
        gesture = null
        if (g.phase != Phase.DRAGGING || !display.valid) return emptyList()
        return listOf(segment(g, display, nowMs, continuing = false).first)
    }

    /** Forgets everything without emitting (a fresh session). */
    fun reset() {
        gesture = null
        lastPointer = null
    }

    private fun pointer(o: JsonObject, d: Display, now: Long): MapResult {
        val x = o.number("x") ?: return malformed()
        val y = o.number("y") ?: return malformed()
        if (!x.isFinite() || !y.isFinite()) return invalid()
        val p = FPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
        lastPointer = p
        val g = gesture ?: return MapResult()
        var next = g
        if (g.phase == Phase.PRESSED && distancePx(g.down, p, d) > SLOP_PX) next = g.copy(phase = Phase.DRAGGING)
        if (next.phase != Phase.DRAGGING) return MapResult()
        val room = MAX_PATH_POINTS - 1
        val pending = if (next.pending.size < room) next.pending + p else next.pending.dropLast(1) + p
        next = next.copy(pending = pending)
        if (now - next.lastEmitMs < SEGMENT_MS) {
            gesture = next
            return MapResult()
        }
        val (action, after) = segment(next, d, now, continuing = true)
        gesture = after
        return MapResult(listOf(action))
    }

    private fun button(o: JsonObject, d: Display, now: Long): MapResult {
        val name = o.string("button") ?: return malformed()
        val pressed = o.bool("pressed") ?: return malformed()
        return when (name) {
            "left" -> if (pressed) leftDown(now) else leftUp(d, now)
            "right" -> if (pressed) MapResult(listOf(Action.Back)) else MapResult()
            "middle" -> if (pressed) MapResult(listOf(Action.Home)) else MapResult()
            else -> MapResult()
        }
    }

    private fun leftDown(now: Long): MapResult {
        val at = lastPointer
        if (at != null && gesture == null) {
            gesture = Gesture(Phase.PRESSED, at, now, at, now, emptyList())
        }
        return MapResult()
    }

    private fun leftUp(d: Display, now: Long): MapResult {
        val g = gesture ?: return MapResult()
        gesture = null
        if (g.phase == Phase.DRAGGING) return MapResult(listOf(segment(g, d, now, continuing = false).first))
        val at = toPx(g.down, d)
        val held = now - g.downMs
        val action = if (held < TAP_MAX_MS) Action.Tap(at)
        else Action.LongPress(at, held.coerceIn(LONG_PRESS_MIN_MS, LONG_PRESS_MAX_MS))
        return MapResult(listOf(action))
    }

    private fun segment(g: Gesture, d: Display, now: Long, continuing: Boolean): Pair<Action.Drag, Gesture> {
        val path = (listOf(g.lastEmitted) + g.pending).map { toPx(it, d) }
        val duration = (now - g.lastEmitMs).coerceIn(1L, MAX_SEGMENT_MS)
        val next = g.copy(lastEmitted = g.pending.lastOrNull() ?: g.lastEmitted, lastEmitMs = now, pending = emptyList())
        return Action.Drag(path, duration, continuing) to next
    }

    private fun scroll(o: JsonObject, d: Display): MapResult {
        val dx = o.number("dx") ?: return malformed()
        val dy = o.number("dy") ?: return malformed()
        val discrete = o.bool("discrete") ?: return malformed()
        if (!dx.isFinite() || !dy.isFinite()) return invalid()
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val moveX = swipeDelta(dx, w, discrete)
        val moveY = swipeDelta(dy, h, discrete)
        if (moveX == 0f && moveY == 0f) return MapResult()
        // The finger moves opposite to the wheel: wheel down shows lower content, so swipe up.
        val from = Point(w / 2, h / 2)
        return MapResult(listOf(Action.Swipe(from, Point(from.x - moveX, from.y - moveY), SWIPE_MS)))
    }

    private fun swipeDelta(value: Double, dim: Float, discrete: Boolean): Float {
        val px = if (discrete) value * WHEEL_STEP * dim else value
        return px.toFloat().coerceIn(-MAX_SWIPE * dim, MAX_SWIPE * dim)
    }

    private fun key(o: JsonObject, d: Display): MapResult {
        val sym = o.long("keysym") ?: return malformed()
        val pressed = o.bool("pressed") ?: return malformed()
        if (sym < 0 || sym > MAX_KEYSYM) return malformed()
        if (!pressed) return MapResult()
        val action: Action? = when (sym) {
            KEY_ESCAPE -> Action.Back
            KEY_SUPER_L, KEY_SUPER_R -> Action.Home
            KEY_ENTER, KEY_KP_ENTER -> Action.PerformImeAction
            KEY_BACKSPACE -> Action.Backspace
            KEY_PAGE_DOWN -> pageSwipe(d, down = true)
            KEY_PAGE_UP -> pageSwipe(d, down = false)
            else -> printable(sym)?.let { Action.SetText(it) }
        }
        return if (action == null) MapResult() else MapResult(listOf(action))
    }

    private fun pageSwipe(d: Display, down: Boolean): Action {
        val x = d.width / 2f
        val half = PAGE_SWIPE * d.height / 2
        val mid = d.height / 2f
        return if (down) Action.Swipe(Point(x, mid + half), Point(x, mid - half), SWIPE_MS)
        else Action.Swipe(Point(x, mid - half), Point(x, mid + half), SWIPE_MS)
    }

    /** X11 keysym to text: Latin-1 printable range, or `0x01000000 + code point`. Else null. */
    private fun printable(sym: Long): String? {
        val cp = when (sym) {
            in 0x20L..0x7eL, in 0xa0L..0xffL -> sym
            in KEYSYM_UNICODE_BASE..(KEYSYM_UNICODE_BASE + MAX_CODE_POINT) -> sym - KEYSYM_UNICODE_BASE
            else -> return null
        }.toInt()
        if (Character.isISOControl(cp) || cp in 0xD800..0xDFFF) return null
        return String(Character.toChars(cp))
    }

    private fun text(o: JsonObject): MapResult {
        val t = o.string("text") ?: return malformed()
        if (t.isEmpty()) return MapResult()
        if (t.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return MapResult(rejected = Reject.TOO_LARGE)
        return MapResult(listOf(Action.SetText(t)))
    }

    private fun malformed() = MapResult(rejected = Reject.MALFORMED)
    private fun invalid() = MapResult(rejected = Reject.INVALID)

    private fun toPx(p: FPoint, d: Display) = Point(
        (p.x * d.width).toFloat().coerceIn(0f, d.width - 1f),
        (p.y * d.height).toFloat().coerceIn(0f, d.height - 1f),
    )

    private fun distancePx(a: FPoint, b: FPoint, d: Display): Float {
        val dx = ((a.x - b.x) * d.width).toFloat()
        val dy = ((a.y - b.y) * d.height).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null // absurdly nested input; the length cap bounds how deep it can go
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.string(key: String): String? = prim(key)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): Double? = prim(key)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    private fun JsonObject.long(key: String): Long? = prim(key)?.takeIf { !it.isString }?.content?.toLongOrNull()
    private fun JsonObject.bool(key: String): Boolean? =
        prim(key)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
}
