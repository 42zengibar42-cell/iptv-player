package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ChannelsActivity : AppCompatActivity() {

    private companion object {
        const val FAV = "__fav__"
        const val ALL = "__all__"
    }

    private lateinit var prefs: Prefs
    private lateinit var account: Account
    private var favorites: Set<String> = emptySet()

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var chAdapter: ChannelAdapter

    /** Soldaki listedeki her satırın kategori kimliği */
    private var catIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val acc = prefs.account
        if (acc == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        account = acc
        favorites = prefs.favorites()

        setContentView(R.layout.activity_channels)
        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        etSearch = findViewById(R.id.etSearch)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tvEmpty)
        findViewById<TextView>(R.id.tvTitle).text = account.name.ifBlank { "IPTV Player" }

        catAdapter = CategoryAdapter { applyFilter() }
        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.itemAnimator = null
        rvCategories.adapter = catAdapter

        chAdapter = ChannelAdapter(
            onClick = ::openChannel,
            onLongClick = ::toggleFavorite,
            onLeft = ::focusSelectedCategory,
            isFavorite = { favorites.contains(it.key) },
        )
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.itemAnimator = null
        rvChannels.adapter = chAdapter

        etSearch.doAfterTextChanged { applyFilter() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { load() }
        findViewById<Button>(R.id.btnAccount).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        if (Repo.account == account && Repo.channels.isNotEmpty()) {
            buildCategories()
        } else {
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::chAdapter.isInitialized) return
        // Oynatıcıdan dönünce son izlenen kanala git
        val i = Repo.lastIndex
        if (i >= 0 && Repo.playlist === chAdapter.items && i < chAdapter.itemCount) {
            rvChannels.scrollToPosition(i)
            rvChannels.post { rvChannels.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        }
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        lifecycleScope.launch {
            try {
                Repo.load(account)
                buildCategories()
            } catch (e: Exception) {
                tvEmpty.text = "Liste yüklenemedi.\n${e.message ?: e.javaClass.simpleName}\n\n\"Yenile\" ile tekrar dene veya \"Hesap\" bilgilerini kontrol et."
                tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun buildCategories() {
        val counts = Repo.channels.groupingBy { it.categoryId }.eachCount()
        val ids = ArrayList<String>()
        val titles = ArrayList<String>()
        ids.add(FAV); titles.add("★ Favoriler")
        ids.add(ALL); titles.add("Tümü (${Repo.channels.size})")
        for (c in Repo.categories) {
            val n = counts[c.id] ?: continue
            ids.add(c.id)
            titles.add("${c.name} ($n)")
        }
        catIds = ids
        catAdapter.items = titles
        val hasFav = Repo.channels.any { favorites.contains(it.key) }
        catAdapter.selected = if (hasFav) 0 else 1
        catAdapter.notifyDataSetChanged()
        applyFilter()
        rvCategories.post { focusSelectedCategory() }
    }

    private fun applyFilter() {
        if (catIds.isEmpty()) return
        val q = etSearch.text.toString().trim()
        val id = catIds.getOrElse(catAdapter.selected) { ALL }
        val list = when {
            q.isNotEmpty() -> Repo.channels.filter { it.name.contains(q, ignoreCase = true) }
            id == FAV -> Repo.channels.filter { favorites.contains(it.key) }
            id == ALL -> Repo.channels
            else -> Repo.channels.filter { it.categoryId == id }
        }
        chAdapter.items = list
        rvChannels.scrollToPosition(0)
        if (list.isEmpty()) {
            tvEmpty.text = when {
                q.isNotEmpty() -> "\"$q\" için kanal bulunamadı."
                id == FAV -> "Henüz favori yok.\nBir kanalın üzerinde OK tuşuna basılı tut."
                else -> "Bu kategoride kanal yok."
            }
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun focusSelectedCategory() {
        val pos = catAdapter.selected
        val vh = rvCategories.findViewHolderForAdapterPosition(pos)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            rvCategories.scrollToPosition(pos)
            rvCategories.post { rvCategories.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    private fun openChannel(pos: Int) {
        if (pos < 0) return
        Repo.playlist = chAdapter.items
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", pos))
    }

    private fun toggleFavorite(pos: Int) {
        if (pos < 0) return
        val c = chAdapter.items[pos]
        val added = prefs.toggleFavorite(c.key)
        favorites = prefs.favorites()
        Toast.makeText(
            this,
            if (added) "Favorilere eklendi: ${c.name}" else "Favorilerden çıkarıldı: ${c.name}",
            Toast.LENGTH_SHORT,
        ).show()
        chAdapter.notifyItemChanged(pos)
    }
}
