package com.knap43.rocker.engine

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The whole engine. An accessibility service because that is the only way an ordinary app may
 * filter hardware keys, and because Android keeps an enabled one alive without a notification.
 *
 * Screen on: [KeyGestures] reads raw key events. Screen off: [ScreenOffSession] takes the volume
 * keys and [VolumeGestures] reads the nudges it receives.
 */
class RockerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var actor: Actor
    private lateinit var keys: KeyGestures
    private lateinit var nudges: VolumeGestures
    private lateinit var session: ScreenOffSession

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff()
                Intent.ACTION_SCREEN_ON -> screenOn()
            }
        }
    }

    override fun onServiceConnected() {
        actor = Actor(this)
        keys = KeyGestures(actor, handler)
        nudges = VolumeGestures(actor, handler)
        session = ScreenOffSession(this, handler) { direction -> nudges.onAdjust(direction) }
        actor.afterMedia = { session.afterMedia() }

        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) screenOff()
        Status.runningState.value = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = ::keys.isInitialized && keys.onKeyEvent(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun screenOff() {
        keys.reset()
        if (actor.config().screenOff) {
            session.start()
            actor.sessionActive = true
        }
    }

    private fun screenOn() {
        session.stop()
        actor.sessionActive = false
        nudges.reset()
    }

    private fun shutdown() {
        if (!::actor.isInitialized || !Status.runningState.value) return
        runCatching { unregisterReceiver(screen) }
        screenOn()
        keys.reset()
        Status.runningState.value = false
    }

    companion object {
        /** Whether the user has switched the service on in Accessibility settings. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, RockerService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
