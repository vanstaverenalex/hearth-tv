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
    tileWdp: Float,
    private val tileHdp: Float,
    private val gapDp: Float,
    private val radiusDp: Float,
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    /** Tile width; the dock changes it so its favourites always fill the row exactly. */
    var tileWdp: Float = tileWdp
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    var dark = true
        set(v) { field = v; notifyDataSetChanged() }

    /** True while the GPU draws the frosted cards' glass (their border included). */
    var gpuGlass = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    private fun rimFor(card: View, focused: Boolean) =
        if (gpuGlass && card.tag == GLASS_TAG && !focused) null else rimDrawable(card.context, radiusDp, focused, dark)

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
        val name: android.widget.TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val w = ctx.dpi(tileWdp)
        val h = ctx.dpi(tileHdp)
        val pad = 44f
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
        val ic = ctx.dpi(tileHdp * 0.42f)
        card.addView(icon, FrameLayout.LayoutParams(ic, ic).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
            topMargin = ctx.dpi(tileHdp * 0.17f)
        })
        // Name only appears on tiles without banner art, so they never look broken.
        val name = android.widget.TextView(ctx).apply {
            style(if (tileWdp > 140f) 10.5f else 9.5f, Fonts.body(ctx, 700), Color.WHITE)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            setPadding(ctx.dpi(8f), 0, ctx.dpi(8f), 0)
        }
        card.addView(name, FrameLayout.LayoutParams(-1, -2).apply {
            gravity = android.view.Gravity.BOTTOM; bottomMargin = ctx.dpi(tileHdp * 0.12f)
        })

        val holder = Holder(root, glow, card, banner, icon, name)
        root.setOnFocusChangeListener { v, focused ->
            animateScale(v, if (focused) 1.06f else 1f)
            v.translationZ = if (focused) 1f else 0f   // draw above neighbours; no shadow layer
            glow.show(focused)
            card.foreground = rimFor(card, focused)
        }
        return holder
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val app = items[position]
        val ctx = h.root.context
        val w = ctx.dpi(tileWdp)
        if (h.card.layoutParams.width != w) {
            val padPx = ctx.dpi(44f)
            h.root.layoutParams = h.root.layoutParams.apply { width = w }
            h.card.layoutParams = h.card.layoutParams.apply { width = w }
            h.glow.layoutParams = h.glow.layoutParams.apply { width = w + 2 * padPx }
        }
        h.root.contentDescription = app.label
        val banner = if (app.isSpecial) null else repo.banners[app.pkg]
        val tint = repo.tints[app.pkg]
        h.card.background = if (banner == null && tint != null && !app.isSpecial) {
            // Tile tinted from the app's own icon colors, lit from the top-left like glass.
            val hsv = FloatArray(3); Color.colorToHSV(tint, hsv)
            val hi = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.9f, minOf(1f, hsv[2] + 0.16f)))
            val lo = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], maxOf(0f, hsv[2] - 0.14f)))
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(hi, lo)).apply { cornerRadius = ctx.dp(radiusDp) }
        } else null
        // Frosted cards (Settings, Fire TV Home, apps without art): the GPU draws their glass when it can.
        val glass = h.card.background == null
        h.card.tag = if (glass) GLASS_TAG else null
        if (glass && !gpuGlass) h.card.background = GlassDrawable(ctx, radiusDp, dark)
        h.card.foreground = rimFor(h.card, h.root.isFocused)

        if (banner != null) {
            h.banner.setImageDrawable(banner)
            h.banner.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
            h.name.visibility = View.GONE
        } else {
            h.banner.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.name.visibility = View.VISIBLE
            h.name.text = app.label
            h.name.setTextColor(if (app.isSpecial && !dark) Hearth.INK_DARK else Color.WHITE)
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

    companion object { const val GLASS_TAG = "hearth-glass" }
}
