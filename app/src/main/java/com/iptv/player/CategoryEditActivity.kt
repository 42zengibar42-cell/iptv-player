package com.iptv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Kategorileri göster/gizle ve sırala. Değişiklikler anında kaydedilir. */
class CategoryEditActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var section: String
    private lateinit var rv: RecyclerView
    private val rows = ArrayList<Category>()
    private var counts: Map<String, Int> = emptyMap()
    private val hidden = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_edit)
        prefs = Prefs(this)
        section = intent.getStringExtra("section") ?: Kind.LIVE
        val data = Repo.sections[section]
        if (data == null) {
            finish()
            return
        }
        findViewById<TextView>(R.id.tvTitle).text = "Kategorileri düzenle – " + when (section) {
            Kind.LIVE -> "Canlı TV"
            Kind.MOVIE -> "Filmler"
            else -> "Diziler"
        }
        counts = data.items.groupingBy { it.categoryId }.eachCount()
        rows.addAll(prefs.sortCategories(section, data.categories).filter { counts.containsKey(it.id) })
        hidden.addAll(prefs.hiddenCategories(section))

        rv = findViewById(R.id.rvEdit)
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.adapter = adapter

        // Dokunmatik ekranda sürükle-bırak ile de sıralanabilir
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                move(vh.bindingAdapterPosition, target.bindingAdapterPosition, keepFocus = null)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        }).attachToRecyclerView(rv)

        findViewById<Button>(R.id.btnShowAll).setOnClickListener {
            hidden.clear()
            prefs.clearHiddenCategories(section)
            adapter.notifyDataSetChanged()
        }
        findViewById<Button>(R.id.btnResetOrder).setOnClickListener {
            prefs.setCategoryOrder(section, emptyList())
            rows.clear()
            rows.addAll(data.categories.filter { counts.containsKey(it.id) })
            adapter.notifyDataSetChanged()
        }
        rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<View>(R.id.tvRow)?.requestFocus() }
    }

    private fun saveOrder() = prefs.setCategoryOrder(section, rows.map { it.id })

    /** keepFocus: taşıdıktan sonra odak hangi düğmede kalsın (R.id.btnUp / btnDown) */
    private fun move(from: Int, to: Int, keepFocus: Int?) {
        if (from < 0 || to < 0 || from >= rows.size || to >= rows.size || from == to) return
        val item = rows.removeAt(from)
        rows.add(to, item)
        adapter.notifyItemMoved(from, to)
        adapter.notifyItemChanged(from)
        adapter.notifyItemChanged(to)
        saveOrder()
        if (keepFocus != null) {
            rv.scrollToPosition(to)
            rv.post { rv.findViewHolderForAdapterPosition(to)?.itemView?.findViewById<View>(keepFocus)?.requestFocus() }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: TextView = v.findViewById(R.id.tvRow)
        val up: Button = v.findViewById(R.id.btnUp)
        val down: Button = v.findViewById(R.id.btnDown)
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category_edit, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = rows[position]
            val visible = c.id !in hidden
            h.row.text = "${if (visible) "☑" else "☐"}   ${position + 1}.  ${c.name}  (${counts[c.id] ?: 0})"
            h.row.alpha = if (visible) 1f else 0.5f
            h.row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p < 0) return@setOnClickListener
                val id = rows[p].id
                val nowHidden = !hidden.contains(id)
                if (nowHidden) hidden.add(id) else hidden.remove(id)
                prefs.setCategoryHidden(section, id, nowHidden)
                notifyItemChanged(p)
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
}
