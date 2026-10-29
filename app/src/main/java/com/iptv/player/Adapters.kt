package com.iptv.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class CategoryAdapter(
    private val onLongClick: ((Int) -> Unit)? = null,
    private val onClick: ((Int) -> Unit)? = null,
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<String> = emptyList()
    var selected = 0

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false) as TextView)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.tv.text = items[position]
        h.tv.isActivated = position == selected
        h.tv.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) select(h.bindingAdapterPosition) }
        h.tv.setOnClickListener {
            select(h.bindingAdapterPosition)
            onClick?.invoke(h.bindingAdapterPosition)
        }
        h.tv.setOnLongClickListener {
            val cb = onLongClick ?: return@setOnLongClickListener false
            cb(h.bindingAdapterPosition)
            true
        }
    }

    fun select(pos: Int) {
        if (pos < 0 || pos == selected) return
        val old = selected
        selected = pos
        notifyItemChanged(old)
        notifyItemChanged(pos)
        onSelect(pos)
    }
}

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onLeft: () -> Unit,
    private val badge: (Channel) -> String,
    private val onFocus: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    /** Genel aramada numara yerine türü göster (TV / Film / Dizi) */
    var showKind = false

    var items: List<Channel> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.tvNum)
        val logo: ImageView = v.findViewById(R.id.ivLogo)
        val name: TextView = v.findViewById(R.id.tvName)
        val fav: TextView = v.findViewById(R.id.tvFav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.num.text = if (showKind) when (c.kind) {
            Kind.LIVE -> "TV"
            Kind.MOVIE -> "Film"
            else -> "Dizi"
        } else (position + 1).toString()
        h.name.text = c.name
        h.fav.text = badge(c)
        if (c.logo == null) {
            h.logo.dispose()
            h.logo.setImageResource(R.drawable.ic_tv)
        } else {
            h.logo.load(c.logo) {
                crossfade(false)
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
        h.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocus?.invoke(h.bindingAdapterPosition)
        }
        h.itemView.setOnLongClickListener {
            onLongClick(h.bindingAdapterPosition)
            true
        }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                onLeft()
                true
            } else {
                false
            }
        }
    }
}
