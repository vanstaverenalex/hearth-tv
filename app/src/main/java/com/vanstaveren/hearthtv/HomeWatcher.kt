package com.vanstaveren.hearthtv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
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
            )
        )
    }

    override fun onInterrupt() {}

    companion object {
        val AMAZON_HOMES = setOf("com.amazon.tv.launcher", "com.amazon.firebat")

        /** Let the family reach Amazon's home (TV inputs, antenna) for a couple of minutes. */
        fun pause(ctx: Context, ms: Long) {
            ctx.getSharedPreferences("hearth", Context.MODE_PRIVATE).edit()
                .putLong("home_paused_until", System.currentTimeMillis() + ms).apply()
        }

        fun pausedUntil(ctx: Context): Long =
            ctx.getSharedPreferences("hearth", Context.MODE_PRIVATE).getLong("home_paused_until", 0L)
    }
}
