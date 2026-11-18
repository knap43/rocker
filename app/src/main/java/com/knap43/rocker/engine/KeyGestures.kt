package com.knap43.rocker.engine

import android.os.Handler
import android.view.KeyEvent
import com.knap43.rocker.data.Gesture
import com.knap43.rocker.data.Key
import com.knap43.rocker.data.Kind

/** Interval of the volume ramp while a captured key is held and its hold is left at "volume". */
private const val RAMP_MS = 100L

/**
 * Screen-on recognition, from raw key events the accessibility service filters. Here we see every
 * down and up and can swallow them, so the gestures are exact: a key whose gestures are all plain
 * volume is let through untouched; any other key is taken over entirely and its volume steps are
 * replayed by hand.
 *
 * With "hide the volume popup" on, a plain-volume key is taken too, but only to be replayed at
 * once: a step on the down and on every auto-repeat, just as the system would, minus the popup.
 */
class KeyGestures(private val actor: Actor, private val handler: Handler) {

    private inner class State(val key: Key) {
        /** We swallowed this press's down, so we own its repeats and its up. */
        var captured = false
        /** Captured only to hide the popup: every down and repeat is one silent volume step. */
        var plain = false
        var down = false
        /** The press already became something (a hold, a ramp, half of "both"): its up is silent. */
        var spent = false
        /** A first short press waiting to see whether a second makes it a double. */
        var tapPending = false

        val holdTimeout = Runnable { onHold(this) }
        val tapTimeout = Runnable {
            tapPending = false
            actor.fire(Gesture.of(key, Kind.PRESS))
        }
        val ramp: Runnable = object : Runnable {
            override fun run() {
                if (!down) return
                actor.volume(key)
                handler.postDelayed(this, RAMP_MS)
            }
        }

        fun cancelTimers() {
            handler.removeCallbacks(holdTimeout)
            handler.removeCallbacks(ramp)
        }

        /** Fire a press still waiting on its double-press window. */
        fun flushTap() {
            if (!tapPending) return
            handler.removeCallbacks(tapTimeout)
            tapTimeout.run()
        }
    }

    private val up = State(Key.UP)
    private val down = State(Key.DOWN)

    fun onKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Key.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Key.DOWN
            else -> return false
        }
        val self = if (key == Key.UP) up else down
        val other = if (key == Key.UP) down else up
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(self, other, event)
            KeyEvent.ACTION_UP -> onUp(self)
            else -> self.captured
        }
    }

    private fun onDown(self: State, other: State, event: KeyEvent): Boolean {
        // Auto-repeats belong to whoever took the first down.
        if (event.repeatCount > 0) {
            if (self.captured && self.plain) actor.volume(self.key)
            return self.captured
        }

        val config = actor.config()
        val bothMapped = actor.mapped(Gesture.BOTH, config)
        self.captured = bothMapped || Kind.entries.any { actor.mapped(self.key, it, config) }
        self.plain = false
        if (!self.captured) {
            if (!config.hidePanel || actor.inCall()) return false
            self.captured = true
            self.plain = true
            self.down = true
            actor.volume(self.key)
            return true
        }

        self.down = true
        self.spent = false
        if (bothMapped && other.down && other.captured && !other.spent) {
            other.cancelTimers()
            other.spent = true
            self.spent = true
            actor.fire(Gesture.BOTH)
            return true
        }
        handler.postDelayed(self.holdTimeout, config.holdMs.toLong())
        return true
    }

    private fun onUp(self: State): Boolean {
        if (!self.captured) return false
        self.captured = false
        self.down = false
        self.cancelTimers()
        if (self.plain) {
            self.plain = false
            return true
        }
        if (self.spent) return true

        if (actor.mapped(self.key, Kind.DOUBLE)) {
            if (self.tapPending) {
                handler.removeCallbacks(self.tapTimeout)
                self.tapPending = false
                actor.fire(Gesture.of(self.key, Kind.DOUBLE))
            } else {
                self.tapPending = true
                handler.postDelayed(self.tapTimeout, actor.config().doubleMs.toLong())
            }
        } else {
            actor.fire(Gesture.of(self.key, Kind.PRESS))
        }
        return true
    }

    private fun onHold(self: State) {
        if (!self.down || self.spent) return
        self.spent = true
        // A short press just before this one keeps its meaning.
        self.flushTap()
        if (actor.mapped(self.key, Kind.HOLD)) {
            actor.fire(Gesture.of(self.key, Kind.HOLD))
        } else {
            self.ramp.run()
        }
    }

    /** Drop everything in flight, e.g. when the screen turns off mid-press. */
    fun reset() {
        listOf(up, down).forEach { state ->
            state.cancelTimers()
            handler.removeCallbacks(state.tapTimeout)
            state.captured = false
            state.plain = false
            state.down = false
            state.spent = false
            state.tapPending = false
        }
    }
}
