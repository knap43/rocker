package com.knap43.rocker.engine

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import com.knap43.rocker.data.Action
import com.knap43.rocker.data.Config
import com.knap43.rocker.data.Gesture
import com.knap43.rocker.data.Key
import com.knap43.rocker.data.Kind
import com.knap43.rocker.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the home screen shows: is the service alive, and what did it last do. */
object Status {
    data class Event(val text: String, val at: Long)

    internal val runningState = MutableStateFlow(false)
    internal val lastState = MutableStateFlow<Event?>(null)

    val running: StateFlow<Boolean> = runningState.asStateFlow()
    val last: StateFlow<Event?> = lastState.asStateFlow()

    internal fun log(text: String) {
        lastState.value = Event(text, System.currentTimeMillis())
    }
}

/**
 * Carries out a recognised gesture. Media commands go out as media-button presses, which Android
 * routes to whichever app last played sound — no notification access needed to find the player.
 */
class Actor(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** True while the screen-off media session owns the volume keys; see [volume]. */
    var sessionActive = false

    /** Called after every media command, so the screen-off session can win the keys back. */
    var afterMedia: (() -> Unit)? = null

    fun config(): Config = Store.current(context)

    /** The action a gesture has right now, after "only while playing" has had its say. */
    fun effective(gesture: Gesture, config: Config = Store.current(context)): Action {
        val action = config.action(gesture)
        if (config.onlyWhilePlaying && action != Action.PLAY_PAUSE && !audio.isMusicActive) {
            return if (gesture == Gesture.BOTH) Action.NOTHING else Action.VOLUME
        }
        return action
    }

    /** Whether a gesture asks for anything beyond what the button does on its own. */
    fun mapped(gesture: Gesture, config: Config = Store.current(context)): Boolean = when (effective(gesture, config)) {
        Action.VOLUME -> false
        Action.NOTHING -> gesture != Gesture.BOTH
        else -> true
    }

    fun mapped(key: Key, kind: Kind, config: Config = Store.current(context)) = mapped(Gesture.of(key, kind), config)

    fun fire(gesture: Gesture, times: Int = 1) {
        val action = effective(gesture)
        when (action) {
            Action.NOTHING -> Unit
            // A double press left at "volume" never reaches here (it resolves as two presses),
            // and a held key ramps through [volume] directly, so this is one step per press.
            Action.VOLUME -> gesture.key?.let { key -> repeat(times) { volume(key) } }
            Action.PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Action.NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            Action.PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        if (action != Action.VOLUME) {
            if (action != Action.NOTHING) tick()
            Status.log("${gesture.label} → ${action.label.lowercase()}" + if (sessionActive) " (screen off)" else "")
        }
    }

    /**
     * One ordinary volume step. With the screen on this goes through the suggested-stream path, so
     * it changes whatever the button would have (ringer when idle, media when playing, a cast
     * device's volume). While our own session holds the keys that path would loop back into it,
     * so then the media stream is adjusted directly.
     */
    fun volume(key: Key) {
        val direction = if (key == Key.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        if (sessionActive) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } else {
            audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun media(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        afterMedia?.invoke()
    }

    private fun tick() {
        if (!Store.current(context).vibrate) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }
}
