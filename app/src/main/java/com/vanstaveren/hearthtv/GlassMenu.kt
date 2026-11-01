package com.vanstaveren.hearthtv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The ☰ menu: a floating glass card with glowing rows. */
class GlassMenu(
    private val ctx: Context,
    private val dark: Boolean,
    private val title: String,
    private val subtitle: String?,
    private val art: Drawable?,
    private val items: List<Item>,
) {
    data class Item(val icon: Int, val label: String, val action: () -> Unit)

    fun show() {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(if (dark) 0.55f else 0.3f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16f), ctx.dpi(20f), ctx.dpi(16f), ctx.dpi(14f))
            clipChildren = false
            clipToPadding = false
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        cornerRadius = ctx.dp(28f)
                        setColor(if (dark) Color.argb(238, 26, 22, 36) else Color.argb(242, 248, 249, 252))
                    },
                    GlassDrawable(ctx, 28f, dark)
                )
            )
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(8f), 0, ctx.dpi(8f), ctx.dpi(12f))
        }
        if (art != null) {
            val thumb = FrameLayout(ctx).apply {
                roundClip(11f)
                background = GradientDrawable().apply { cornerRadius = ctx.dp(11f); setColor(Color.argb(60, 255, 255, 255)) }
            }
            thumb.addView(ImageView(ctx).apply {
                setImageDrawable(art); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            head.addView(thumb, LinearLayout.LayoutParams(ctx.dpi(80f), ctx.dpi(45f)).apply { rightMargin = ctx.dpi(12f) })
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply { text = title; style(21f, Fonts.display(ctx, 400), ink) })
        if (subtitle != null) texts.addView(TextView(ctx).apply {
            text = subtitle; style(12f, Fonts.body(ctx, 600), if (dark) Color.argb(190, 255, 255, 255) else Color.argb(190, 11, 23, 38))
            setPadding(0, ctx.dpi(4f), 0, 0)
        })
        head.addView(texts)
        card.addView(head)

        for (item in items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(ctx.dpi(14f), 0, ctx.dpi(14f), 0)
                setOnClickListener { d.dismiss(); item.action() }
                setOnFocusChangeListener { v, f ->
                    v.background = if (f) android.graphics.drawable.LayerDrawable(
                        arrayOf(hearthGradient(ctx, 22f, 0.42f), rimDrawable(ctx, 22f, true, dark))
                    ) else null
                    (v as LinearLayout).let { r ->
                        val c = if (f) Color.WHITE else ink
                        (r.getChildAt(0) as ImageView).setColorFilter(c)
                        (r.getChildAt(1) as TextView).setTextColor(c)
                    }
                }
            }
            row.addView(ImageView(ctx).apply { setImageResource(item.icon); setColorFilter(ink) },
                LinearLayout.LayoutParams(ctx.dpi(18f), ctx.dpi(18f)).apply { rightMargin = ctx.dpi(12f) })
            row.addView(TextView(ctx).apply { text = item.label; style(16f, Fonts.body(ctx, 600), ink) })
            card.addView(row, LinearLayout.LayoutParams(-1, ctx.dpi(44f)).apply { topMargin = ctx.dpi(3f) })
        }

        val wrap = FrameLayout(ctx).apply { clipChildren = false }
        wrap.addView(card, FrameLayout.LayoutParams(ctx.dpi(380f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        d.setContentView(wrap)
        d.show()
        card.post { if (card.childCount > 1) card.getChildAt(1).requestFocus() }
    }
}
