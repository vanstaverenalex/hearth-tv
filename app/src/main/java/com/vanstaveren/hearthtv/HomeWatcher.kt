package com.vanstaveren.hearthtv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes Hearth the real home screen. Fire TVs won't let you change the default
 * launcher, so this watches for Amazon's home screen opening (after the Home button
 * or power-on) and immediately brings Hearth to the front instead.
 */
class HomeWatcher : AccessibilityService() {

    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in AMAZON_HOMES) return
        if (System.currentTimeMillis() < pausedUntil(this)) return
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 800) return
        lastLaunch = now
        Log.i("HearthHome", "Amazon home opened ($pkg) → Hearth")
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ).putExtra(MainActivity.EXTRA_OPEN, "home")
        )
    }

    /**
     * The remote's Live / Guide button opens Hearth's guide from anywhere (even inside Hulu).
     * Unrecognised special buttons are remembered so the setup menu can show their code.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in GUIDE_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i("HearthHome", "guide key $code → Hearth guide")
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    ).putExtra(MainActivity.EXTRA_OPEN, "guide")
                )
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && code !in COMMON_KEYS) {
            getSharedPreferences("hearth", Context.MODE_PRIVATE).edit()
                .putString("last_key", "${KeyEvent.keyCodeToString(code)} ($code)").apply()
        }
        return false
    }

    override fun onInterrupt() {}

    companion object {
        val AMAZON_HOMES = setOf("com.amazon.tv.launcher", "com.amazon.firebat")

        /** Live / Guide / TV buttons found on Fire TV and smart-TV remotes. */
        val GUIDE_KEYS = setOf(KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_DVR, KeyEvent.KEYCODE_LAST_CHANNEL)

        private val COMMON_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_SEARCH,
        )

        /** Let the family reach Amazon's home (TV inputs, antenna) for a couple of minutes. */
        fun pause(ctx: Context, ms: Long) {
            ctx.getSharedPreferences("hearth", Context.MODE_PRIVATE).edit()
                .putLong("home_paused_until", System.currentTimeMillis() + ms).apply()
        }

        fun pausedUntil(ctx: Context): Long =
            ctx.getSharedPreferences("hearth", Context.MODE_PRIVATE).getLong("home_paused_until", 0L)
    }
}
