package com.knap43.rocker.engine

import android.content.Context
import android.content.Intent
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler

/** Re-claim the keys this often while the screen is off, in case the player took them back. */
private const val REASSERT_EVERY_MS = 15_000L

/**
 * With the screen off, volume keys go to the media session Android picks for volume: the one that
 * most recently started playing. This session claims to be playing, plays nothing, and routes its
 * volume to a [VolumeProvider], so the nudges come to us instead of the player.
 *
 * It exists only while the screen is off. When the real player resumes after a pause it moves back
 * to the top and would take the keys, so after every media command — and every so often anyway —
 * the session flips its state to step back in front.
 */
class ScreenOffSession(
    private val context: Context,
    private val handler: Handler,
    private val onAdjust: (Int) -> Unit,
) {
    private var session: MediaSession? = null

    val active get() = session != null

    private val periodic = object : Runnable {
        override fun run() {
            reassert()
            handler.postDelayed(this, REASSERT_EVERY_MS)
        }
    }
    private val once = Runnable { reassert() }
    private val again = Runnable { reassert() }

    fun start() {
        if (session != null) return
        session = MediaSession(context, "Rocker").apply {
            // Without a callback the framework drops volume adjustments before they reach the
            // provider: both are delivered through the callback's handler.
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    // Media buttons should reach the real player, which owns the audio. Note it if not.
                    Status.log("A media button reached Rocker instead of the player")
                    return true
                }
            }, handler)
            setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) = onAdjust(direction)
            })
            setPlaybackState(state(PlaybackState.STATE_PLAYING))
            isActive = true
        }
        handler.postDelayed(periodic, REASSERT_EVERY_MS)
    }

    /** After a play/pause or skip: the player re-announces itself within a second or two. */
    fun afterMedia() {
        if (session == null) return
        handler.removeCallbacks(once)
        handler.removeCallbacks(again)
        handler.postDelayed(once, 700)
        handler.postDelayed(again, 2_000)
    }

    fun stop() {
        handler.removeCallbacks(periodic)
        handler.removeCallbacks(once)
        handler.removeCallbacks(again)
        session?.run {
            isActive = false
            release()
        }
        session = null
    }

    /** A change from not-playing to playing moves a session to the front of the volume queue. */
    private fun reassert() {
        session?.run {
            setPlaybackState(state(PlaybackState.STATE_PAUSED))
            setPlaybackState(state(PlaybackState.STATE_PLAYING))
        }
    }

    private fun state(state: Int) = PlaybackState.Builder()
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .setActions(0)
        .build()
}
