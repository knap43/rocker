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

/** A nudge for a key that is "down" this long after the last one means its release went missing. */
private const val LOST_RELEASE_MS = 400L

/**
 * Screen-off recognition. With the screen off Android hands volume keys not to accessibility
 * services but to the media session that owns volume, so all we get are "raise" and "lower"
 * nudges — one when the key goes down and, on most phones, a neutral one when it comes up.
 *
 * Where the release nudge arrives (assumed, as stock Android sends it), the key counts as down from
 * its nudge until its release, and a hold is timed exactly as with the screen on. Android sends no
 * auto-repeat with the screen off — the input dispatcher only repeats keys it delivers to a window
 * — so a hold that changes volume is stepped by a timer at the key-repeat rate.
 *
 * Where a phone turns out not to send it, the old guesswork applies: a press is a lone nudge, and a hold is
 * recognised from auto-repeat, a quick stream of nudges after the repeat delay, where a ROM sends it.
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
    /** Whether the key is down: only known once [releases] is. */
    private var down = false
    /**
     * Whether this phone sends a nudge on release. Stock Android always does, so it is assumed until
     * a nudge arrives for a key that should still be down, long after any auto-repeat would have.
     */
    private var releases = true

    private val decide = Runnable { resolve() }
    private val holdTimer = Runnable { key?.let { if (down && !holding) startHold(it) } }
    private val ramp = object : Runnable {
        override fun run() {
            val k = key ?: return
            if (!down || !holding || !holdIsVolume) return
            actor.volume(k)
            handler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

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

        if (releases && down && k == key && now - lastAt > ViewConfiguration.getKeyRepeatTimeout() + LOST_RELEASE_MS) {
            // The key went up without telling us: this phone (or this press) sends no release nudge.
            releases = false
            down = false
            cancelTimers()
            holding = false
            count = 0
        }

        // Auto-repeat: with releases known, any nudge while the key is down; otherwise a quick one.
        val repeat = k == key && if (releases) down else now - lastAt < REPEAT_GAP_MS
        if (repeat) {
            lastAt = now
            if (!holding) startHold(k) else if (holdIsVolume && !releases) actor.volume(k)
            return
        }

        // The other key: whatever the first one was doing is finished.
        if (key != null && k != key) {
            cancelTimers()
            holding = false
            resolve()
        }
        key = k
        lastAt = now
        pressAt = now
        holding = false
        down = true

        val double = actor.mapped(k, Kind.DOUBLE, config)
        val hold = actor.mapped(k, Kind.HOLD, config)
        cancelTimers()

        if (releases) {
            // A plain-volume hold ramps after the usual repeat delay; a mapped one fires at holdMs.
            if (hold) handler.postDelayed(holdTimer, config.holdMs.toLong())
            else handler.postDelayed(holdTimer, ViewConfiguration.getKeyRepeatTimeout().toLong())
        }

        if (!double && !hold) {
            count = 0
            actor.fire(Gesture.of(k, Kind.PRESS))
            return
        }

        count++
        if (releases) return // The release decides.

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
        cancelTimers()
        // Presses before this one belong to no double press any more: let them count.
        val earlier = count - 1
        if (earlier > 0) actor.fire(Gesture.of(k, Kind.PRESS), times = earlier)
        holding = true
        count = 0
        holdIsVolume = !actor.mapped(k, Kind.HOLD)
        if (!holdIsVolume) {
            actor.fire(Gesture.of(k, Kind.HOLD))
        } else if (releases) {
            // The press already stepped the volume if it was plain; keep stepping while held.
            ramp.run()
        } else {
            actor.volume(k)
        }
    }

    private fun onRelease(now: Long) {
        releases = true
        down = false
        handler.removeCallbacks(holdTimer)
        handler.removeCallbacks(ramp)
        if (holding) {
            holding = false
            return
        }
        val k = key ?: return
        if (count == 0) return
        handler.removeCallbacks(decide)
        val config = actor.config()
        when {
            // No hold timer was running (releases weren't known yet), but the key stayed down long enough.
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

    private fun cancelTimers() {
        handler.removeCallbacks(decide)
        handler.removeCallbacks(holdTimer)
        handler.removeCallbacks(ramp)
    }

    fun reset() {
        cancelTimers()
        key = null
        count = 0
        holding = false
        down = false
    }
}
