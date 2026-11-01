package com.iptv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Bir kategorideki kanalları sırala.
 * İki yol: (1) kanalda OK → "taşıma modu", ▲▼ ile kaydır, OK ile bırak; (2) ⤒ ▲ ▼ düğmeleri.
 * Sıra kanal anahtarıyla kaydedilir; Yenile'de bozulmaz, yeni kanallar sona gelir.
 */
class ChannelOrderActivity : AppCompatActivity() {

    companion object {
        var channels: List<Channel> = emptyList()
    }

    private lateinit var prefs: Prefs
    private lateinit var section: String
    private lateinit var catId: String
    private lateinit var rv: RecyclerView
    private val rows = ArrayList<Channel>()

    /** Taşıma modunda tutulan satır (-1: yok) */
    private var moving = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_order)
        prefs = Prefs(this)
        section = intent.getStringExtra("section") ?: Kind.LIVE
        catId = intent.getStringExtra("catId") ?: run { finish(); return }
        if (channels.isEmpty()) {
            finish()
            return
        }
        rows.addAll(channels)
        findViewById<TextView>(R.id.tvTitle).text = "Kanalları sırala – ${intent.getStringExtra("name") ?: ""}"

        rv = findViewById(R.id.rvOrder)
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.adapter = adapter

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            prefs.setChannelOrder(section, catId, emptyList())
            // Sağlayıcı sırası: kayıt silindi, listeyi orijinal sıraya döndür
            val original = Repo.sections[section]?.items
                ?.filter { catId == "__all__" || it.categoryId == catId }
            if (original != null) {
                val keys = rows.mapTo(HashSet()) { it.key }
                rows.clear()
                rows.addAll(original.filter { it.key in keys })
            }
            moving = -1
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Sağlayıcının sırasına dönüldü", Toast.LENGTH_SHORT).show()
        }
        rv.post { focusRow(0, R.id.tvRow) }
    }

    override fun onPause() {
        super.onPause()
        if (::rv.isInitialized) save()
    }

    private fun save() = prefs.setChannelOrder(section, catId, rows.map { it.key })

    private fun focusRow(pos: Int, viewId: Int) {
        rv.scrollToPosition(pos)
        rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.findViewById<View>(viewId)?.requestFocus() }
    }

    private fun move(from: Int, to: Int, keepFocus: Int) {
        if (from < 0 || from >= rows.size) return
        val target = to.coerceIn(0, rows.size - 1)
        if (target == from) return
        val item = rows.removeAt(from)
        rows.add(target, item)
        if (moving == from) moving = target
        adapter.notifyItemMoved(from, target)
        adapter.notifyItemRangeChanged(minOf(from, target), kotlin.math.abs(from - target) + 1)
        focusRow(target, keepFocus)
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: TextView = v.findViewById(R.id.tvRow)
        val top: Button = v.findViewById(R.id.btnTop)
        val up: Button = v.findViewById(R.id.btnUp)
        val down: Button = v.findViewById(R.id.btnDown)
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_order, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = rows[position]
            val isMoving = position == moving
            h.row.text = (if (isMoving) "⇕  " else "") + "${position + 1}.  ${c.name}"
            h.row.isActivated = isMoving

            h.row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0) return@setOnClickListener
                val old = moving
                moving = if (moving == p) -1 else p
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(p)
                if (moving >= 0) {
                    Toast.makeText(this@ChannelOrderActivity, "▲ ▼ ile taşı, OK ile bırak", Toast.LENGTH_SHORT).show()
                }
                focusRow(p, R.id.tvRow)
            }
            // Taşıma modunda ▲▼ satırı kaydırır
            h.row.setOnKeyListener { _, keyCode, event ->
                val p = h.bindingAdapterPosition
                if (p != moving || event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { move(p, p - 1, R.id.tvRow); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { move(p, p + 1, R.id.tvRow); true }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { move(p, p - 10, R.id.tvRow); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { move(p, p + 10, R.id.tvRow); true }
                    else -> false
                }
            }
            h.top.setOnClickListener {
                val p = h.bindingAdapterPosition
                move(p, 0, R.id.btnTop)
                Toast.makeText(this@ChannelOrderActivity, "En üste taşındı: ${rows[0].name}", Toast.LENGTH_SHORT).show()
            }
            h.up.setOnClickListener {
                val p = h.bindingAdapterPosition
                move(p, p - 1, R.id.btnUp)
            }
            h.down.setOnClickListener {
                val p = h.bindingAdapterPosition
                move(p, p + 1, R.id.btnDown)
            }
        }
    }

    override fun onBackPressed() {
        if (moving >= 0) {
            val p = moving
            moving = -1
            adapter.notifyItemChanged(p)
            return
        }
        save()
        Toast.makeText(this, "Sıralama kaydedildi", Toast.LENGTH_SHORT).show()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
