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
        val cls = event.className?.toString().orEmpty()
        val now = System.currentTimeMillis()
        val decision = decide(cls, now)
        Diag.log(this, "window $pkg/$cls → $decision")
        if (decision != "take") return
        if (now - lastLaunch < 800) return
        lastLaunch = now
        amazonUiOpen = false
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ).putExtra(MainActivity.EXTRA_OPEN, "home")
        )
    }

    /**
     * Amazon's launcher package also hosts its own screens (Settings pages, the Appstore, app start-up
     * transitions, TV inputs). Only take over when it's really the Amazon home screen:
     * right after the Home button, or a launcher window that isn't one of those other screens.
     */
    private fun decide(cls: String, now: Long): String {
        if (now - homePressedAt < 4000) return "take"                     // Home button: always ours
        if (now < pausedUntil(this)) return "skip:paused"
        if (amazonUiOpen && now - launchedAt < 15 * 60_000L) return "skip:amazon-ui-open"
        if (now - launchedAt < 20_000) return "skip:app-starting"          // Fire OS shows launcher frames while an app loads
        if (NOT_HOME.containsMatchIn(cls)) return "skip:not-home"
        return "take"
    }

    /**
     * The remote's Live / Guide button opens Hearth's guide from anywhere (even inside Hulu).
     * Unrecognised special buttons are remembered so the setup menu can show their code.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_DOWN) {
            homePressedAt = System.currentTimeMillis()
            amazonUiOpen = false
            Diag.log(this, "key HOME")
            return false
        }
        if (code in GUIDE_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Diag.log(this, "key guide $code → Hearth guide")
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

        /** Amazon screens that live inside the launcher package but are not the home screen. */
        private val NOT_HOME = Regex(
            "(?i)setting|store|venezia|search|profile|notification|input|hdmi|tuner|livetv|player|playback|" +
                "detail|dialog|alexa|voice|account|parental|update|ota|keyboard|ime|purchase|login|signin|" +
                "screensaver|ambient|photo|apps|library|manage|error|toast|popup|overlay|transition|splash|loading"
        )

        @Volatile private var homePressedAt = 0L
        @Volatile private var launchedAt = 0L
        @Volatile private var amazonUiOpen = false

        /** Hearth just opened something: don't fight the launcher frames Fire OS shows while it starts. */
        fun noteLaunch(ctx: Context, pkg: String, amazonUi: Boolean) {
            launchedAt = System.currentTimeMillis()
            amazonUiOpen = amazonUi
            Diag.log(ctx, "launch $pkg${if (amazonUi) " (Amazon screen)" else ""}")
        }

        /** Hearth is in front again. */
        fun noteHearthShown() { amazonUiOpen = false; launchedAt = 0L }

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

/** Small on-TV diagnostics log (Android/data/<app>/files/hearth-log.txt), read by the PC's log script. */
object Diag {
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    @Synchronized fun log(ctx: Context, msg: String) {
        Log.i("HearthHome", msg)
        try {
            val f = java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "hearth-log.txt")
            if (f.length() > 256 * 1024) f.writeText(f.readLines().takeLast(800).joinToString("\n", postfix = "\n"))
            f.appendText("${fmt.format(java.util.Date())}  $msg\n")
        } catch (_: Exception) {}
    }
}
