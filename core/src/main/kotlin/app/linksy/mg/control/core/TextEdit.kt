package app.linksy.mg.control.core

data class EditResult(val text: String, val cursor: Int)

object TextEdit {
    fun insert(current: String, selStart: Int, selEnd: Int, insert: String): EditResult = EditResult(current, 0)
    fun backspace(current: String, selStart: Int, selEnd: Int): EditResult = EditResult(current, 0)
}
