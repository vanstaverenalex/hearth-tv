package com.vanstaveren.hearthtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Holiday easter egg: Santa's sleigh and four reindeer glide across the top of the
 * home screen, galloping legs, a gentle bob, and a twinkling golden trail.
 * Original artwork drawn in code; only animates while flying (no cost otherwise).
 */
class SantaSleigh(context: Context) : View(context) {

    private val d = context.resources.displayMetrics.density
    private var progress = -1f          // 0..1 while flying
    private var timeSec = 0f
    private var anim: ValueAnimator? = null
    private val sparks = ArrayList<FloatArray>()   // x, y, age, life, size, hue(0 gold / 1 white)
    private var lastSpark = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    // Palette
    private val suit = Color.rgb(224, 56, 62)
    private val fur = Color.rgb(255, 246, 238)
    private val sleighBody = Color.rgb(163, 36, 59)
    private val sleighDark = Color.rgb(118, 22, 44)
    private val gold = Color.rgb(245, 194, 107)
    private val deer = Color.rgb(138, 90, 60)
    private val deerLight = Color.rgb(196, 154, 108)
    private val antler = Color.rgb(222, 196, 150)
    private val skin = Color.rgb(246, 205, 176)

    init {
        isFocusable = false
        visibility = INVISIBLE
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    val flying get() = progress >= 0f

    companion object {
        /** Early November through Epiphany. */
        fun inSeason(): Boolean {
            val c = Calendar.getInstance()
            val m = c.get(Calendar.MONTH); val day = c.get(Calendar.DAY_OF_MONTH)
            return m == Calendar.NOVEMBER || m == Calendar.DECEMBER || (m == Calendar.JANUARY && day <= 6)
        }
    }

    fun fly() {
        if (flying || width == 0) return
        sparks.clear(); lastSpark = 0f
        visibility = VISIBLE; alpha = 1f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 15_000; interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                timeSec = progress * 15f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { progress = -1f; visibility = INVISIBLE }
            })
            start()
        }
    }

    fun stop() {
        if (!flying) return
        animate().alpha(0f).setDuration(250).withEndAction { anim?.cancel(); progress = -1f; visibility = INVISIBLE; alpha = 1f }.start()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (!flying) return
        val total = 165f * d                         // length of the whole team
        val x = -total + (width + total * 1.6f) * progress
        val baseY = height * 0.52f
        val bob = sin(timeSec * 2f * PI.toFloat() * 0.7f) * 2.2f * d
        val tilt = cos(timeSec * 2f * PI.toFloat() * 0.7f) * 1.6f

        updateSparks(x + 8f * d, baseY + bob + 6f * d)
        drawSparks(canvas)

        canvas.save()
        canvas.translate(x, baseY + bob)
        canvas.rotate(-2f + tilt, 60f * d, 0f)
        canvas.scale(d, d)                           // draw below in dp units

        // A faint warm aura so the team reads over any background.
        p.shader = RadialGradient(80f, 0f, 95f, Color.argb(26, 255, 230, 190), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawOval(-20f, -28f, 180f, 28f, p); p.shader = null

        // Reins from sleigh to the lead pair
        line.color = gold; line.strokeWidth = 0.9f
        canvas.drawLine(38f, -2f, 150f, -6f, line)

        // Four reindeer, each slightly out of step
        val gallop = timeSec * 2f * PI.toFloat() * 2.4f
        for (i in 0 until 4) {
            val rx = 58f + i * 27f
            val ry = -4f - i * 1.2f + sin(gallop + i * 0.9f) * 0.8f
            drawReindeer(canvas, rx, ry, gallop + i * 0.9f)
        }

        drawSleigh(canvas, 0f, 0f)
        canvas.restore()
    }

    private fun drawReindeer(c: Canvas, x: Float, y: Float, phase: Float) {
        // Legs (behind body): front pair reaches forward, back pair pushes back
        line.color = deer; line.strokeWidth = 1.6f
        val s = sin(phase); val s2 = sin(phase + PI.toFloat())
        c.drawLine(x + 7f, y + 3f, x + 11f + s * 4f, y + 10f, line)
        c.drawLine(x + 5f, y + 3f, x + 8f + s2 * 3f, y + 10f, line)
        c.drawLine(x - 6f, y + 3f, x - 10f + s2 * 4f, y + 9f, line)
        c.drawLine(x - 8f, y + 3f, x - 12f + s * 3f, y + 10f, line)
        // Body
        p.color = deer; r.set(x - 11f, y - 3.5f, x + 10f, y + 5f); c.drawOval(r, p)
        p.color = deerLight; r.set(x - 7f, y + 1f, x + 7f, y + 5f); c.drawOval(r, p)
        // Tail
        p.color = fur; c.drawCircle(x - 11f, y - 2f, 1.6f, p)
        // Neck + head
        path.reset(); path.moveTo(x + 6f, y - 1f); path.lineTo(x + 11f, y - 8f); path.lineTo(x + 14f, y - 7f); path.lineTo(x + 10f, y + 1f); path.close()
        p.color = deer; c.drawPath(path, p)
        r.set(x + 10.5f, y - 11f, x + 18.5f, y - 6f); c.drawOval(r, p)        // head
        p.color = deerLight; r.set(x + 15.5f, y - 9.5f, x + 19.5f, y - 6.5f); c.drawOval(r, p) // muzzle
        p.color = Color.rgb(40, 24, 18); c.drawCircle(x + 14.2f, y - 9.2f, 0.7f, p)              // eye
        p.color = deer; path.reset(); path.moveTo(x + 11.5f, y - 10f); path.lineTo(x + 9.5f, y - 12.5f); path.lineTo(x + 12.8f, y - 11f); path.close(); c.drawPath(path, p) // ear
        // Antlers
        line.color = antler; line.strokeWidth = 1.1f
        c.drawLine(x + 12.5f, y - 11f, x + 10.5f, y - 17f, line)
        c.drawLine(x + 11.2f, y - 14.5f, x + 8.4f, y - 15.5f, line)
        c.drawLine(x + 10.8f, y - 16f, x + 12.2f, y - 18.8f, line)
        c.drawLine(x + 14.2f, y - 11f, x + 15.4f, y - 16.5f, line)
        c.drawLine(x + 14.9f, y - 14f, x + 17.6f, y - 15f, line)
        // Gold harness band
        line.color = gold; line.strokeWidth = 1f
        c.drawLine(x + 5f, y - 3f, x + 6.5f, y + 4.5f, line)
    }

    private fun drawSleigh(c: Canvas, x: Float, y: Float) {
        // Runners with a curl at the front
        line.color = gold; line.strokeWidth = 1.6f
        path.reset()
        path.moveTo(x - 6f, y + 11f); path.lineTo(x + 34f, y + 11f)
        path.cubicTo(x + 41f, y + 11f, x + 43f, y + 5f, x + 38f, y + 3.5f)
        c.drawPath(path, line)
        c.drawLine(x + 4f, y + 6f, x + 4f, y + 11f, line)
        c.drawLine(x + 26f, y + 6f, x + 26f, y + 11f, line)

        // Presents peeking out the back
        p.color = Color.rgb(64, 168, 120); r.set(x - 1f, y - 9f, x + 8f, y - 1f); c.drawRoundRect(r, 1.2f, 1.2f, p)
        p.color = Color.rgb(90, 140, 230); r.set(x + 5f, y - 13f, x + 13f, y - 3f); c.drawRoundRect(r, 1.2f, 1.2f, p)
        line.color = gold; line.strokeWidth = 1f
        c.drawLine(x + 3.5f, y - 9f, x + 3.5f, y - 1f, line)
        c.drawLine(x + 9f, y - 13f, x + 9f, y - 3f, line)
        c.drawLine(x + 5f, y - 8f, x + 13f, y - 8f, line)

        // Body: a scooped sleigh with a curled front
        path.reset()
        path.moveTo(x - 4f, y - 4f)
        path.lineTo(x + 2f, y + 7f)
        path.lineTo(x + 30f, y + 7f)
        path.cubicTo(x + 38f, y + 6f, x + 40f, y - 2f, x + 36f, y - 6f)
        path.cubicTo(x + 33f, y - 9f, x + 29f, y - 6f, x + 31f, y - 3f)
        path.lineTo(x + 30f, y - 1f)
        path.lineTo(x + 6f, y - 1f)
        path.close()
        p.color = sleighBody; c.drawPath(path, p)
        p.color = sleighDark; r.set(x + 2f, y + 3f, x + 30f, y + 7f); c.drawRect(r, p)
        line.color = gold; line.strokeWidth = 1.1f
        c.drawLine(x + 3f, y + 2.5f, x + 30f, y + 2.5f, line)
        p.color = gold; c.drawCircle(x + 34.5f, y - 4.5f, 1.3f, p)

        // Santa: red coat, white trim, beard, hat with pom-pom, waving arm
        p.color = suit; r.set(x + 12f, y - 13f, x + 25f, y + 1f); c.drawRoundRect(r, 5f, 5f, p)      // body
        p.color = fur; r.set(x + 12f, y - 2.5f, x + 25f, y + 0.5f); c.drawRoundRect(r, 1.5f, 1.5f, p)  // coat hem
        p.color = Color.rgb(40, 30, 30); r.set(x + 12f, y - 6.5f, x + 25f, y - 4.8f); c.drawRect(r, p) // belt
        p.color = gold; r.set(x + 17.2f, y - 7f, x + 19.8f, y - 4.3f); c.drawRect(r, p)                // buckle
        p.color = skin; c.drawCircle(x + 20.5f, y - 16.5f, 3.4f, p)                                     // face
        p.color = fur; path.reset()                                                                      // beard
        path.moveTo(x + 17f, y - 16f); path.quadTo(x + 18f, y - 9.5f, x + 21.5f, y - 10f); path.quadTo(x + 24.5f, y - 11f, x + 24f, y - 16f); path.close()
        c.drawPath(path, p)
        p.color = suit; path.reset()                                                                     // hat
        path.moveTo(x + 16.8f, y - 18.5f); path.quadTo(x + 18f, y - 25f, x + 24.5f, y - 22f); path.lineTo(x + 24f, y - 18.5f); path.close()
        c.drawPath(path, p)
        p.color = fur; r.set(x + 16.5f, y - 19.6f, x + 24.6f, y - 17.6f); c.drawRoundRect(r, 1f, 1f, p)
        c.drawCircle(x + 14.8f, y - 21.5f, 1.7f, p)                                                      // pom-pom (hat tips back)
        // Waving arm
        val wave = sin(timeSec * 2f * PI.toFloat() * 1.1f) * 10f
        c.save(); c.rotate(-40f + wave, x + 15f, y - 10f)
        p.color = suit; r.set(x + 13.5f, y - 18f, x + 16.5f, y - 9f); c.drawRoundRect(r, 1.5f, 1.5f, p)
        p.color = fur; r.set(x + 13.2f, y - 19.2f, x + 16.8f, y - 17.4f); c.drawRect(r, p)
        p.color = Color.rgb(40, 30, 30); c.drawCircle(x + 15f, y - 20.4f, 1.4f, p)                      // mitten
        c.restore()
    }

    // ---------------------------------------------------------------- golden trail

    private fun updateSparks(tx: Float, ty: Float) {
        if (timeSec - lastSpark > 0.05f) {
            lastSpark = timeSec
            repeat(2) {
                sparks += floatArrayOf(
                    tx + Random.nextFloat() * 6f * d, ty + (Random.nextFloat() - 0.5f) * 8f * d,
                    timeSec, 0.9f + Random.nextFloat() * 0.8f, (1.2f + Random.nextFloat() * 1.6f) * d,
                    if (Random.nextFloat() < 0.35f) 1f else 0f,
                )
            }
        }
        sparks.removeAll { timeSec - it[2] > it[3] }
    }

    private fun drawSparks(c: Canvas) {
        for (s in sparks) {
            val age = (timeSec - s[2]) / s[3]
            val a = ((1f - age) * 255).toInt().coerceIn(0, 255)
            val twinkle = 0.6f + 0.4f * sin((timeSec + s[0]) * 20f)
            val size = s[4] * (1f - age * 0.5f) * twinkle
            val y = s[1] + age * 5f * d
            p.color = if (s[5] > 0.5f) Color.argb(a, 255, 255, 255) else Color.argb(a, 255, 214, 130)
            // Four-point twinkle star
            path.reset()
            path.moveTo(s[0], y - size * 2f); path.lineTo(s[0] + size * 0.45f, y - size * 0.45f)
            path.lineTo(s[0] + size * 2f, y); path.lineTo(s[0] + size * 0.45f, y + size * 0.45f)
            path.lineTo(s[0], y + size * 2f); path.lineTo(s[0] - size * 0.45f, y + size * 0.45f)
            path.lineTo(s[0] - size * 2f, y); path.lineTo(s[0] - size * 0.45f, y - size * 0.45f); path.close()
            c.drawPath(path, p)
        }
    }
}
