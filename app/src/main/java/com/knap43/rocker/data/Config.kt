package com.knap43.rocker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Key(val label: String) { UP("Volume up"), DOWN("Volume down") }

enum class Kind(val label: String) { PRESS("Press"), DOUBLE("Double press"), HOLD("Hold") }

/** Everything the rocker can be taught. BOTH is the two keys pressed together. */
enum class Gesture(val key: Key?, val kind: Kind?) {
    UP_PRESS(Key.UP, Kind.PRESS),
    UP_DOUBLE(Key.UP, Kind.DOUBLE),
    UP_HOLD(Key.UP, Kind.HOLD),
    DOWN_PRESS(Key.DOWN, Kind.PRESS),
    DOWN_DOUBLE(Key.DOWN, Kind.DOUBLE),
    DOWN_HOLD(Key.DOWN, Kind.HOLD),
    BOTH(null, null);

    val label: String
        get() = if (this == BOTH) "Both together" else "${kind!!.label} ${key!!.label.lowercase()}"

    /** What a gesture does out of the box: holds skip, both together pauses, the rest is plain volume. */
    val default: Action
        get() = when (this) {
            UP_HOLD -> Action.NEXT
            DOWN_HOLD -> Action.PREVIOUS
            BOTH -> Action.PLAY_PAUSE
            else -> Action.VOLUME
        }

    /** Volume makes no sense for both keys at once; there, "nothing" stands in for it. */
    val choices: List<Action>
        get() = if (this == BOTH) Action.entries - Action.VOLUME else Action.entries

    companion object {
        fun of(key: Key, kind: Kind) = entries.first { it.key == key && it.kind == kind }
    }
}

/**
 * VOLUME means "behave like an ordinary volume button" — for a press a single step, for a
 * double press two steps, for a hold the usual ramp. It is the absence of a mapping.
 */
enum class Action(val label: String) {
    VOLUME("Change volume"),
    PLAY_PAUSE("Play / pause"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    NOTHING("Do nothing"),
}

data class Config(
    val actions: Map<Gesture, Action> = Gesture.entries.associateWith { it.default },
    /** How long a key must stay down to count as held. */
    val holdMs: Int = 450,
    /** How soon a second press must follow the first to make a double press. */
    val doubleMs: Int = 300,
    val screenOff: Boolean = true,
    /** When nothing is playing, only play/pause gestures stay live; everything else is plain volume. */
    val onlyWhilePlaying: Boolean = false,
    val vibrate: Boolean = true,
) {
    fun action(gesture: Gesture): Action = actions[gesture] ?: gesture.default

    fun action(key: Key, kind: Kind): Action = action(Gesture.of(key, kind))
}

/** SharedPreferences behind a StateFlow. The service runs in the app's process, so both share it. */
object Store {
    private const val PREFS = "rocker"
    private val state = MutableStateFlow(Config())
    private var loaded = false

    fun config(context: Context): StateFlow<Config> {
        if (!loaded) load(context.applicationContext)
        return state.asStateFlow()
    }

    fun current(context: Context): Config = config(context).value

    fun update(context: Context, change: (Config) -> Config) {
        if (!loaded) load(context.applicationContext)
        val next = change(state.value)
        state.value = next
        prefs(context).edit().apply {
            next.actions.forEach { (gesture, action) -> putString("action_${gesture.name}", action.name) }
            putInt("hold_ms", next.holdMs)
            putInt("double_ms", next.doubleMs)
            putBoolean("screen_off", next.screenOff)
            putBoolean("only_while_playing", next.onlyWhilePlaying)
            putBoolean("vibrate", next.vibrate)
        }.apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        val defaults = Config()
        state.value = Config(
            actions = Gesture.entries.associateWith { gesture ->
                p.getString("action_${gesture.name}", null)
                    ?.let { name -> Action.entries.firstOrNull { it.name == name } }
                    ?.takeIf { it in gesture.choices }
                    ?: gesture.default
            },
            holdMs = p.getInt("hold_ms", defaults.holdMs),
            doubleMs = p.getInt("double_ms", defaults.doubleMs),
            screenOff = p.getBoolean("screen_off", defaults.screenOff),
            onlyWhilePlaying = p.getBoolean("only_while_playing", defaults.onlyWhilePlaying),
            vibrate = p.getBoolean("vibrate", defaults.vibrate),
        )
        loaded = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
