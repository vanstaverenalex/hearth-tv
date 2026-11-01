package com.vanstaveren.hearthtv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * App tiles with no labels: the app's own banner art fills the tile.
 * Focus = gentle lift + the Hearth purple/pink glow behind it.
 */
class TileAdapter(
    private val repo: AppRepository,
    private val tileWdp: Float,
    private val tileHdp: Float,
    private val gapDp: Float,
    private val radiusDp: Float,
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    var dark = true
        set(v) { field = v; notifyDataSetChanged() }

    var items: List<AppEntry> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    /** Keep a fixed number of columns in a grid (null for the dock). */
    var gridColumns: Int? = null

    class Holder(
        val root: FrameLayout,
        val glow: GlowView,
        val card: FrameLayout,
        val banner: ImageView,
        val icon: ImageView,
    ) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val w = ctx.dpi(tileWdp)
        val h = ctx.dpi(tileHdp)
        val pad = 36f
        val padPx = ctx.dpi(pad)

        val root = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = RecyclerView.LayoutParams(w, h).apply {
                val g = ctx.dpi(gapDp / 2)
                setMargins(g, g, g, g)
            }
        }
        val glow = GlowView(ctx, radiusDp, pad)
        root.addView(glow, FrameLayout.LayoutParams(w + 2 * padPx, h + 2 * padPx).apply {
            setMargins(-padPx, -padPx, -padPx, -padPx)
        })
        val card = FrameLayout(ctx).apply { roundClip(radiusDp) }
        root.addView(card, FrameLayout.LayoutParams(w, h))
        val banner = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(banner, FrameLayout.LayoutParams(-1, -1))
        val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val ic = ctx.dpi(tileHdp * 0.5f)
        card.addView(icon, FrameLayout.LayoutParams(ic, ic).apply { gravity = android.view.Gravity.CENTER })

        val holder = Holder(root, glow, card, banner, icon)
        root.setOnFocusChangeListener { v, focused ->
            animateScale(v, if (focused) 1.06f else 1f)
            v.translationZ = if (focused) v.dp(8f) else 0f
            glow.show(focused)
            card.foreground = rimDrawable(ctx, radiusDp, focused, dark)
        }
        return holder
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val app = items[position]
        val ctx = h.root.context
        h.root.contentDescription = app.label
        h.card.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(radiusDp)
            setColor(if (dark) Color.argb(235, 22, 22, 34) else Color.argb(235, 246, 248, 252))
        }
        h.card.foreground = rimDrawable(ctx, radiusDp, h.root.isFocused, dark)

        val banner = if (app.isSpecial) null else repo.banners[app.pkg]
        if (banner != null) {
            h.banner.setImageDrawable(banner)
            h.banner.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
        } else {
            h.banner.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            when (app.pkg) {
                Specials.SETTINGS -> {
                    h.icon.setImageResource(R.drawable.ic_settings)
                    h.icon.setColorFilter(if (dark) Color.WHITE else Hearth.INK_DARK)
                }
                Specials.FIRE_HOME -> {
                    h.icon.setImageResource(R.drawable.ic_input)
                    h.icon.setColorFilter(if (dark) Color.WHITE else Hearth.INK_DARK)
                }
                else -> {
                    h.icon.clearColorFilter()
                    h.icon.setImageDrawable(repo.icons[app.pkg])
                }
            }
        }

        h.root.setOnClickListener { onOpen(app) }
        h.root.setOnLongClickListener { onMenu(app); true }
        h.root.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_MENU) {
                if (e.action == KeyEvent.ACTION_UP) onMenu(app)
                true
            } else false
        }
    }
}
