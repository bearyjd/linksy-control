package app.linksy.mg.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display.DEFAULT_DISPLAY
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.linksy.mg.control.core.Action
import app.linksy.mg.control.core.ActionQueue
import app.linksy.mg.control.core.ControlEngine
import app.linksy.mg.control.core.Display
import app.linksy.mg.control.core.Point
import app.linksy.mg.control.core.TextEdit

/**
 * Applies [Action]s from the core engine. All calls arrive on the main thread (the bridge's
 * handler). Subscribes to no accessibility events and reads only the focused input field.
 */
class ControlAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val engine = ControlEngine(SystemClock::elapsedRealtime)
    private val queue = ActionQueue()
    private var inFlight = false
    private var lastStroke: StrokeDescription? = null

    private val tick = object : Runnable {
        override fun run() {
            if (engine.isActive()) {
                enqueue(engine.tick(display())) // may release a held stroke after a display change
                main.postDelayed(this, TICK_MS)
            } else {
                finish(engine.tick(display())) // timed out: drop queued input, keep the release
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // no event types subscribed

    override fun onInterrupt() = endSession()

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        endSession()
        instance = null
        return super.onUnbind(intent)
    }

    fun isSessionActive() = engine.isActive()

    fun beginSession() {
        engine.begin()
        // A pending release from a replaced session stays queued, and lastStroke stays with it.
        queue.beginSession()
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    fun endSession() {
        main.removeCallbacks(tick)
        finish(engine.end(display()))
    }

    fun inject(json: String) {
        val result = engine.inject(json, display())
        // Rejected input is dropped silently; never logged (may hold typed text).
        if (engine.isActive()) {
            enqueue(result.actions)
            main.removeCallbacks(tick)
            main.postDelayed(tick, TICK_MS)
        } else {
            finish(result.actions)
        }
    }

    private fun display(): Display {
        val dm = DisplayMetrics()
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") d.getRealMetrics(dm) // current rotation, per call
        return Display(dm.widthPixels, dm.heightPixels)
    }

    private fun enqueue(actions: List<Action>) {
        queue.enqueue(actions)
        pump()
    }

    /** The session is over: queued ordinary input is dropped; only [releases] and earlier releases run. */
    private fun finish(releases: List<Action>) {
        queue.endSession(releases)
        pump()
    }

    private fun pump() {
        while (!inFlight) {
            val a = queue.next() ?: return
            when (a) {
                is Action.Tap -> gesture(strokeOf(listOf(a.at), TAP_MS, false, fresh = true))
                is Action.LongPress -> gesture(strokeOf(listOf(a.at), a.durationMs, false, fresh = true))
                is Action.Swipe -> gesture(strokeOf(listOf(a.from, a.to), a.durationMs, false, fresh = true))
                is Action.Drag -> gesture(strokeOf(a.path, a.durationMs, a.continuing, fresh = false))
                Action.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
                Action.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
                Action.PerformImeAction -> imeEnter()
                Action.Backspace -> editFocused { text, s, e -> TextEdit.backspace(text, s, e) }
                is Action.SetText -> editFocused { text, s, e -> TextEdit.insert(text, s, e, a.text) }
            }
        }
    }

    private fun strokeOf(points: List<Point>, durationMs: Long, willContinue: Boolean, fresh: Boolean): StrokeDescription {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val prev = if (fresh) null else lastStroke
        val stroke = if (prev != null) prev.continueStroke(path, 0, durationMs, willContinue)
        else StrokeDescription(path, 0, durationMs, willContinue)
        lastStroke = if (willContinue) stroke else null
        return stroke
    }

    private fun gesture(stroke: StrokeDescription) {
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done()
            override fun onCancelled(g: GestureDescription?) {
                lastStroke = null // a cancelled stroke cannot be continued
                queue.strokeLost()
                done()
            }
        }
        inFlight = true
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, main)
        if (!accepted) done()
    }

    private fun done() {
        inFlight = false
        pump()
    }

    /** Runs [block] on the focused, editable, non-password field (never password fields), then recycles. */
    private fun <T> withFocusedField(block: (AccessibilityNodeInfo) -> T): T? {
        val root = rootInActiveWindow ?: return null
        var node: AccessibilityNodeInfo? = null
        try {
            node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            return if (node.isEditable && !node.isPassword) block(node) else null
        } finally {
            if (android.os.Build.VERSION.SDK_INT < 33) { // recycle() is a no-op from 33 and deprecated
                @Suppress("DEPRECATION") node?.recycle()
                @Suppress("DEPRECATION") root.recycle()
            }
        }
    }

    private fun editFocused(edit: (String, Int, Int) -> app.linksy.mg.control.core.EditResult) {
        withFocusedField { node -> editNode(node, edit) }
    }

    private fun editNode(node: AccessibilityNodeInfo, edit: (String, Int, Int) -> app.linksy.mg.control.core.EditResult) {
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        val result = edit(current, node.textSelectionStart, node.textSelectionEnd)
        val set = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result.text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun imeEnter() {
        if (android.os.Build.VERSION.SDK_INT < 30) return // no IME-action API before Android 11
        withFocusedField { it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
    }

    companion object {
        /** Set while the service is enabled and connected; null otherwise. */
        @Volatile var instance: ControlAccessibilityService? = null
            private set

        private const val TICK_MS = 5_000L
        private const val TAP_MS = 50L
    }
}
