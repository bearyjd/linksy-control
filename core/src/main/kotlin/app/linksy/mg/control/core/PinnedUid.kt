package app.linksy.mg.control.core

class PinnedUid(
    private val resolve: () -> Int?,
    private val clock: () -> Long,
    private val refreshMs: Long = 10_000L,
) {
    @Synchronized fun matches(sender: Int): Boolean = false
}
