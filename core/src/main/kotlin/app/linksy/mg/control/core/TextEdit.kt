package app.linksy.mg.control.core

data class EditResult(val text: String, val cursor: Int)

/** Pure text edits for the focused field; the Android glue reads and writes the node. */
object TextEdit {
    /** Replace the selection (or insert at the cursor). A selection of -1 means unknown: append. */
    fun insert(current: String, selStart: Int, selEnd: Int, insert: String): EditResult {
        val (s, e) = range(current, selStart, selEnd)
        return EditResult(current.substring(0, s) + insert + current.substring(e), s + insert.length)
    }

    /** Delete the selection, or the character (whole surrogate pair) before the cursor. */
    fun backspace(current: String, selStart: Int, selEnd: Int): EditResult {
        val (s, e) = range(current, selStart, selEnd)
        val from = when {
            s != e -> s
            s == 0 -> return EditResult(current, 0)
            s >= 2 && Character.isLowSurrogate(current[s - 1]) && Character.isHighSurrogate(current[s - 2]) -> s - 2
            else -> s - 1
        }
        return EditResult(current.substring(0, from) + current.substring(e), from)
    }

    private fun range(text: String, a: Int, b: Int): Pair<Int, Int> {
        if (a < 0 || b < 0) return text.length to text.length
        val lo = minOf(a, b).coerceIn(0, text.length)
        val hi = maxOf(a, b).coerceIn(0, text.length)
        return lo to hi
    }
}
