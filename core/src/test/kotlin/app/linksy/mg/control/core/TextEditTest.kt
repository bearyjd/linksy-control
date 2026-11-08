package app.linksy.mg.control.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TextEditTest {
    @Test fun insertAtCursor() = assertEquals(EditResult("heXllo", 3), TextEdit.insert("hello", 2, 2, "X"))
    @Test fun insertReplacesSelection() = assertEquals(EditResult("hXo", 2), TextEdit.insert("hello", 1, 4, "X"))
    @Test fun unknownSelectionAppends() = assertEquals(EditResult("helloX", 6), TextEdit.insert("hello", -1, -1, "X"))
    @Test fun reversedAndOutOfRangeSelectionIsNormalised() {
        assertEquals(EditResult("hXo", 2), TextEdit.insert("hello", 4, 1, "X"))
        assertEquals(EditResult("helloX", 6), TextEdit.insert("hello", 99, 120, "X"))
    }
    @Test fun insertIntoEmpty() = assertEquals(EditResult("a", 1), TextEdit.insert("", 0, 0, "a"))
    @Test fun backspaceDeletesBeforeCursor() = assertEquals(EditResult("hllo", 1), TextEdit.backspace("hello", 2, 2))
    @Test fun backspaceDeletesSelection() = assertEquals(EditResult("ho", 1), TextEdit.backspace("hello", 1, 4))
    @Test fun backspaceAtStartIsNoop() = assertEquals(EditResult("hello", 0), TextEdit.backspace("hello", 0, 0))
    @Test fun backspaceRemovesWholeSurrogatePair() =
        assertEquals(EditResult("ab", 2), TextEdit.backspace("ab😀", 4, 4))
    @Test fun backspaceWithUnknownSelectionDeletesLast() =
        assertEquals(EditResult("hell", 4), TextEdit.backspace("hello", -1, -1))
}
