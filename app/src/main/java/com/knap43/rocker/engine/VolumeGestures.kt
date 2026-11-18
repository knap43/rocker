package com.knap43.rocker.engine

import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.view.ViewConfiguration
import com.knap43.rocker.data.Gesture
import com.knap43.rocker.data.Key
import com.knap43.rocker.data.Kind

/** Auto-repeat arrives every ~50 ms; no human double-presses that fast. */
private const val REPEAT_GAP_MS = 150L

/** How long to wait after a second event to tell a double press from the start of a hold. */
private const val CONFIRM_MS = 160L

/**
 * Screen-off recognition. With the screen off Android hands volume keys not to accessibility
 * services but to the media session that owns volume, so all we get are "raise" and "lower"
 * nudges — no downs, no ups. A press is a lone nudge; a hold shows up as the key's auto-repeat,
 * a quick stream of nudges after the repeat delay. Many Android versions also send a neutral
 * nudge on release, and when one arrives it is used to finish gestures sooner.
 *
 * Both keys at once can't be told apart here, so "both together" is screen-on only.
 */
class VolumeGestures(private val actor: Actor, private val handler: Handler) {
    private var key: Key? = null
    /** Separate presses seen in the current gesture. */
    private var count = 0
    private var lastAt = 0L
    private var pressAt = 0L
    private var holding = false
    private var holdIsVolume = false

    private val decide = Runnable { resolve() }

    fun onAdjust(direction: Int) {
        val now = SystemClock.uptimeMillis()
        when {
            direction > 0 -> onNudge(Key.UP, now)
            direction < 0 -> onNudge(Key.DOWN, now)
            direction == AudioManager.ADJUST_SAME -> onRelease(now)
        }
    }

    private fun onNudge(k: Key, now: Long) {
        val config = actor.config()

        if (k == key && now - lastAt < REPEAT_GAP_MS) {
            lastAt = now
            if (!holding) startHold(k) else if (holdIsVolume) actor.volume(k)
            return
        }

        // The other key: whatever the first one was doing is finished.
        if (key != null && k != key) {
            handler.removeCallbacks(decide)
            resolve()
        }
        key = k
        lastAt = now
        pressAt = now
        holding = false

        val double = actor.mapped(k, Kind.DOUBLE, config)
        val hold = actor.mapped(k, Kind.HOLD, config)
        if (!double && !hold) {
            count = 0
            actor.fire(Gesture.of(k, Kind.PRESS))
            return
        }

        count++
        handler.removeCallbacks(decide)
        val wait = if (count >= 2) {
            // Either a real second press, or the first auto-repeat of a hold; the next 160 ms tell.
            CONFIRM_MS
        } else {
            maxOf(
                if (double) config.doubleMs.toLong() else 0L,
                if (hold) ViewConfiguration.getKeyRepeatTimeout() + CONFIRM_MS else 0L,
            )
        }
        handler.postDelayed(decide, wait)
    }

    private fun startHold(k: Key) {
        handler.removeCallbacks(decide)
        holding = true
        count = 0
        holdIsVolume = !actor.mapped(k, Kind.HOLD)
        if (holdIsVolume) actor.volume(k) else actor.fire(Gesture.of(k, Kind.HOLD))
    }

    private fun onRelease(now: Long) {
        if (holding) {
            holding = false
            return
        }
        val k = key ?: return
        if (count == 0) return
        handler.removeCallbacks(decide)
        val config = actor.config()
        when {
            // No auto-repeat reached us, but the key plainly stayed down long enough.
            count == 1 && now - pressAt >= config.holdMs && actor.mapped(k, Kind.HOLD, config) -> {
                count = 0
                actor.fire(Gesture.of(k, Kind.HOLD))
            }
            count >= 2 || !actor.mapped(k, Kind.DOUBLE, config) -> resolve()
            // One short press: wait out the double-press window from the release.
            else -> handler.postDelayed(decide, config.doubleMs.toLong())
        }
    }

    private fun resolve() {
        val k = key ?: return
        val presses = count
        count = 0
        if (holding || presses == 0) return
        if (presses >= 2 && actor.mapped(k, Kind.DOUBLE)) {
            actor.fire(Gesture.of(k, Kind.DOUBLE))
        } else {
            actor.fire(Gesture.of(k, Kind.PRESS), times = presses)
        }
    }

    fun reset() {
        handler.removeCallbacks(decide)
        key = null
        count = 0
        holding = false
    }
}
