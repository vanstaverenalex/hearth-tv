package com.vanstaveren.hearthtv

import android.graphics.Outline
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val repo: AppRepository,
    private val large: Boolean,
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    var items: List<AppEntry> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tile: FrameLayout = view.findViewById(R.id.tile)
        val banner: ImageView = view.findViewById(R.id.banner)
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val h = Holder(view)
        val d = parent.resources.displayMetrics.density
        val w = ((if (large) 192 else 150) * d).toInt()
        val ht = ((if (large) 108 else 84) * d).toInt()
        h.tile.layoutParams = h.tile.layoutParams.apply { width = w; height = ht }
        h.label.layoutParams = h.label.layoutParams.apply { width = w }

        // Rounded corners on app artwork.
        val radius = 12 * d
        h.tile.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
        }
        h.tile.clipToOutline = true

        h.tile.setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.1f else 1f
            v.animate().scaleX(s).scaleY(s).translationZ(if (focused) 12 * d else 0f).setDuration(140).start()
            h.label.animate().scaleX(s).scaleY(s).translationY(if (focused) 6 * d else 0f).setDuration(140).start()
            h.label.setTextColor(v.context.getColor(if (focused) R.color.text else R.color.text_dim))
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val app = items[position]
        h.label.text = app.label

        val banner = if (app.isSpecial) null else repo.banners[app.pkg]
        if (banner != null) {
            h.banner.setImageDrawable(banner)
            h.banner.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
        } else {
            h.banner.visibility = View.GONE
            h.icon.visibility = View.VISIBLE
            h.icon.setImageDrawable(
                when (app.pkg) {
                    Specials.SETTINGS -> h.icon.context.getDrawable(R.drawable.ic_settings)
                    Specials.HIDDEN -> h.icon.context.getDrawable(R.drawable.ic_eye)
                    else -> repo.icons[app.pkg]
                }
            )
        }

        h.tile.setOnClickListener { onOpen(app) }
        h.tile.setOnLongClickListener { onMenu(app); true }
        h.tile.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) onMenu(app)
                true
            } else false
        }
    }
}
