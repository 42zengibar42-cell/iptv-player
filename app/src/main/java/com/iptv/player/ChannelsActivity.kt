package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChannelsActivity : AppCompatActivity() {

    private companion object {
        const val TAB_FAV = "fav"
        const val TAB_LIST = "list"
        const val ALL = "__all__"
        const val EDIT = "__edit__"
    }

    private lateinit var prefs: Prefs
    private lateinit var account: Account

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var loading: LinearLayout
    private lateinit var tvLoading: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var catAdapter: CategoryAdapter
    private lateinit var chAdapter: ChannelAdapter
    private lateinit var tabs: Map<String, Button>
    private lateinit var btnSort: Button

    /** Kind.LIVE / Kind.MOVIE / Kind.SERIES / TAB_FAV */
    private var tab = Kind.LIVE
    private var loadJob: Job? = null

    /** O an gösterilen bölümün öğeleri (gizli kategoriler hariç) */
    private var sectionItems: List<Channel> = emptyList()

    /** Bölümün kategorileri (gizliler dahil), yeniden çizmek için */
    private var sectionCategories: List<Category> = emptyList()

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
        Repo.account = acc

        setContentView(R.layout.activity_channels)
        rvCategories = findViewById(R.id.rvCategories)
        rvChannels = findViewById(R.id.rvChannels)
        etSearch = findViewById(R.id.etSearch)
        loading = findViewById(R.id.loading)
        tvLoading = findViewById(R.id.tvLoading)
        tvEmpty = findViewById(R.id.tvEmpty)

        catAdapter = CategoryAdapter(onLongClick = ::categoryMenu, onClick = ::categoryClick) { applyFilter() }
        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.itemAnimator = null
        rvCategories.adapter = catAdapter

        chAdapter = ChannelAdapter(
            onClick = ::openItem,
            onLongClick = ::toggleFavorite,
            onLeft = ::focusSelectedCategory,
            badge = { prefs.badge(it.key) },
        )
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.itemAnimator = null
        rvChannels.adapter = chAdapter

        tabs = mapOf(
            Kind.LIVE to findViewById(R.id.tabLive),
            Kind.MOVIE to findViewById(R.id.tabMovies),
            Kind.SERIES to findViewById(R.id.tabSeries),
            TAB_LIST to findViewById(R.id.tabList),
            TAB_FAV to findViewById(R.id.tabFav),
        )
        tabs.forEach { (key, btn) -> btn.setOnClickListener { showTab(key, focusList = true) } }

        etSearch.doAfterTextChanged { applyFilter() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { reload() }
        btnSort = findViewById(R.id.btnSort)
        btnSort.setOnClickListener { cycleSort() }
        findViewById<Button>(R.id.btnAccount).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        showTab(Kind.LIVE, focusList = true)
    }

    override fun onResume() {
        super.onResume()
        if (!::chAdapter.isInitialized) return
        // Favoriler / Listem / kategori ayarları başka ekrandan değişmiş olabilir
        if (editorOpened) {
            editorOpened = false
            if (!isCollection) rebuildSection()
            return
        }
        if (isCollection) refreshCollection() else chAdapter.notifyDataSetChanged()
        // Oynatıcıdan dönünce son izlenen kanala git
        val i = Repo.lastIndex
        if (tab == Kind.LIVE && i >= 0 && Repo.playlist === chAdapter.items && i < chAdapter.itemCount) {
            rvChannels.scrollToPosition(i)
            rvChannels.post { rvChannels.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
        }
    }

    private fun showTab(key: String, focusList: Boolean) {
        tab = key
        tabs.forEach { (k, b) -> b.isActivated = k == key }
        btnSort.text = sortLabel()
        tvEmpty.visibility = View.GONE
        loadJob?.cancel()
        loading.visibility = View.GONE
        if (isCollection) {
            sectionItems = prefs.items(collection())
            buildCategories(focusList)
            return
        }
        val cached = Repo.sections[key]
        if (cached != null) {
            setSection(cached)
            buildCategories(focusList, visibleCategories())
        } else {
            load(key, force = false, focusList = focusList)
        }
    }

    private fun sectionName(k: String) = when (k) {
        Kind.LIVE -> "kanallar"
        Kind.MOVIE -> "filmler"
        else -> "diziler"
    }

    private fun load(key: String, force: Boolean, focusList: Boolean) {
        loadJob?.cancel()
        chAdapter.items = emptyList()
        catAdapter.items = emptyList()
        catAdapter.notifyDataSetChanged()
        catIds = emptyList()
        tvLoading.text = "${sectionName(key).replaceFirstChar { it.uppercase() }} yükleniyor…"
        loading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        loadJob = lifecycleScope.launch {
            try {
                val data = Repo.loadSection(account, key, force)
                if (key == Kind.LIVE) prefs.migrateOldFavorites(data.items)
                if (tab != key) return@launch
                loading.visibility = View.GONE
                setSection(data)
                buildCategories(focusList, visibleCategories())
                if (force) {
                    Toast.makeText(
                        this@ChannelsActivity,
                        "Liste güncellendi: ${data.items.size} ${sectionName(key)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                tvEmpty.text = "Liste yüklenemedi.\n${e.message ?: e.javaClass.simpleName}\n\n\"⟳ Yenile\" ile tekrar dene veya ⚙ Hesap bilgilerini kontrol et."
                tvEmpty.visibility = View.VISIBLE
            } finally {
                if (tab == key) loading.visibility = View.GONE
            }
        }
    }

    /** Sağlayıcının güncel listesini yeniden indirir; diğer bölümleri de sıfırlar */
    private fun reload() {
        Repo.sections.clear()
        if (isCollection) {
            showTab(tab, focusList = false)
            Toast.makeText(this, "Listeler bir sonraki açılışta güncel indirilecek", Toast.LENGTH_SHORT).show()
        } else {
            load(tab, force = true, focusList = false)
        }
    }

    private fun buildCategories(focusList: Boolean, categories: List<Category> = emptyList()) {
        val ids = ArrayList<String>()
        val titles = ArrayList<String>()
        ids.add(ALL); titles.add("Tümü (${sectionItems.size})")
        if (isCollection) {
            val counts = sectionItems.groupingBy {
                if (it.kind == Kind.EPISODE) Kind.SERIES else it.kind
            }.eachCount()
            for ((k, label) in listOf(Kind.LIVE to "Canlı TV", Kind.MOVIE to "Filmler", Kind.SERIES to "Diziler")) {
                val n = counts[k] ?: continue
                ids.add(k); titles.add("$label ($n)")
            }
        } else {
            val counts = sectionItems.groupingBy { it.categoryId }.eachCount()
            for (c in categories) {
                val n = counts[c.id] ?: continue
                ids.add(c.id)
                titles.add("${c.name} ($n)")
            }
            ids.add(EDIT)
            titles.add(if (hiddenCount > 0) "✎ Kategorileri düzenle ($hiddenCount gizli)" else "✎ Kategorileri düzenle")
        }
        catIds = ids
        catAdapter.items = titles
        val last = if (isCollection) -1 else ids.indexOf(prefs.lastCategory(tab))
        catAdapter.selected = if (last > 0 && ids[last] != EDIT) last else 0
        catAdapter.notifyDataSetChanged()
        rvCategories.scrollToPosition(catAdapter.selected)
        applyFilter()
        if (focusList) rvCategories.post { focusSelectedCategory() }
    }

    private fun applyFilter() {
        if (catIds.isEmpty()) {
            chAdapter.items = emptyList()
            showEmptyIfNeeded()
            return
        }
        val q = etSearch.text.toString().trim()
        val id = catIds.getOrElse(catAdapter.selected) { ALL }
        if (!isCollection && id != EDIT) prefs.setLastCategory(tab, id)
        var list = when {
            id == EDIT && q.isEmpty() -> emptyList()
            id == ALL -> sectionItems
            isCollection && id == Kind.SERIES -> sectionItems.filter { it.kind == Kind.SERIES || it.kind == Kind.EPISODE }
            isCollection -> sectionItems.filter { it.kind == id }
            else -> sectionItems.filter { it.categoryId == id }
        }
        if (q.isNotEmpty()) list = (if (id == ALL) sectionItems else list).filter { it.name.contains(q, ignoreCase = true) }
        chAdapter.items = sorted(list)
        rvChannels.scrollToPosition(0)
        showEmptyIfNeeded()
    }

    private fun showEmptyIfNeeded() {
        if (loading.visibility == View.VISIBLE) return
        if (chAdapter.itemCount > 0) {
            tvEmpty.visibility = View.GONE
            return
        }
        val q = etSearch.text.toString().trim()
        tvEmpty.text = when {
            q.isNotEmpty() -> "\"$q\" için sonuç bulunamadı."
            catIds.getOrNull(catAdapter.selected) == EDIT -> "OK'a bas: kategorileri sırala, göster veya gizle."
            tab == TAB_FAV -> "Henüz favori yok.\nBir kanal, film ya da dizinin üzerinde OK'a basılı tut → Favorilere ekle."
            tab == TAB_LIST -> "Listen boş.\nBir film ya da dizinin üzerinde OK'a basılı tut → Listeme ekle."
            sectionItems.isEmpty() -> "Bu hesapta ${sectionName(tab)} yok."
            else -> "Bu kategoride içerik yok."
        }
        tvEmpty.visibility = View.VISIBLE
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

    private fun openItem(pos: Int) {
        val items = chAdapter.items
        val c = items.getOrNull(pos) ?: return
        when {
            c.isSeriesFolder -> startActivity(
                Intent(this, EpisodesActivity::class.java)
                    .putExtra("seriesId", c.streamId)
                    .putExtra("name", c.name)
                    .putExtra("logo", c.logo)
            )
            c.kind == Kind.MOVIE && c.key.startsWith("v:") -> {
                DetailActivity.item = c
                startActivity(Intent(this, DetailActivity::class.java))
            }
            c.isVod -> {
                Repo.playlist = listOf(c)
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", 0).putExtra("vod", true))
            }
            else -> {
                // Canlı: ▲▼ ile listedeki diğer kanallara geçilebilsin
                val live = if (tab == Kind.LIVE) items else items.filter { it.kind == Kind.LIVE }
                Repo.playlist = live
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", live.indexOf(c)))
            }
        }
    }

    private var hiddenCount = 0

    private fun setSection(data: SectionData) {
        sectionCategories = data.categories
        val hidden = prefs.hiddenCategories(tab)
        val present = data.items.mapTo(HashSet()) { it.categoryId }
        hiddenCount = hidden.count { it in present }
        val visible = if (hidden.isEmpty()) data.items else data.items.filter { it.categoryId !in hidden }
        // "Tümü" listesi de kategori sırasına göre dizilsin
        val rank = HashMap<String, Int>()
        prefs.sortCategories(tab, data.categories).forEachIndexed { i, c -> rank[c.id] = i }
        sectionItems = if (prefs.categoryOrder(tab).isEmpty()) visible
        else visible.sortedBy { rank[it.categoryId] ?: Int.MAX_VALUE }
    }

    private fun visibleCategories(): List<Category> {
        val hidden = prefs.hiddenCategories(tab)
        return prefs.sortCategories(tab, sectionCategories).filter { it.id !in hidden }
    }

    /** Kategoriyi gizledikten / geri getirdikten sonra listeyi yeniden kur */
    private fun rebuildSection() {
        val data = Repo.sections[tab] ?: return
        setSection(data)
        buildCategories(focusList = true, visibleCategories())
    }

    private fun categoryMenu(pos: Int) {
        if (isCollection) return
        val id = catIds.getOrNull(pos) ?: return
        if (id == ALL || id == EDIT) return
        val name = sectionCategories.firstOrNull { it.id == id }?.name ?: return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(arrayOf("🚫  Bu kategoriyi gizle")) { _, _ ->
                prefs.setCategoryHidden(tab, id, true)
                Toast.makeText(this, "Gizlendi: $name", Toast.LENGTH_SHORT).show()
                rebuildSection()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private var editorOpened = false

    private fun categoryClick(pos: Int) {
        if (catIds.getOrNull(pos) != EDIT || Repo.sections[tab] == null) return
        editorOpened = true
        startActivity(Intent(this, CategoryEditActivity::class.java).putExtra("section", tab))
    }

    private val collator = java.text.Collator.getInstance(java.util.Locale("tr", "TR")).apply {
        strength = java.text.Collator.PRIMARY
    }

    /** Adın başındaki "DE - ", "|TR| " gibi ön ekleri sıralamada yok say */
    private val prefix = Regex("""^\s*([|\[(]?[A-Z]{2,3}[|\])]?\s*[-:|]\s*|[|\[(][A-Z]{2,3}[|\])]\s*)""")

    private fun sortKey(name: String) = name.replaceFirst(prefix, "").trim()

    private fun sorted(list: List<Channel>): List<Channel> = when (prefs.sortMode(tab)) {
        1 -> list.sortedWith { a, b -> collator.compare(sortKey(a.name), sortKey(b.name)) }
        2 -> list.sortedByDescending { it.added }
        else -> list
    }

    private fun sortLabel() = when (prefs.sortMode(tab)) {
        1 -> "⇅ A–Z"
        2 -> "⇅ En yeni"
        else -> "⇅ Sıra"
    }

    private fun cycleSort() {
        // "En yeni" sadece filmler ve dizilerde anlamlı
        val max = if (tab == Kind.MOVIE || tab == Kind.SERIES) 2 else 1
        val next = (prefs.sortMode(tab) + 1) % (max + 1)
        prefs.setSortMode(tab, next)
        btnSort.text = sortLabel()
        Toast.makeText(
            this,
            when (next) {
                1 -> "Sıralama: A'dan Z'ye"
                2 -> "Sıralama: en yeni eklenenler önce"
                else -> "Sıralama: sağlayıcının sırası"
            },
            Toast.LENGTH_SHORT,
        ).show()
        applyFilter()
    }

    private val isCollection get() = tab == TAB_FAV || tab == TAB_LIST

    private fun collection() = if (tab == TAB_FAV) Prefs.FAV else Prefs.LIST

    /** Favoriler / Listem sekmesini, seçili kategoriyi koruyarak yeniler */
    private fun refreshCollection() {
        val sel = catIds.getOrNull(catAdapter.selected)
        sectionItems = prefs.items(collection())
        buildCategories(focusList = false)
        val i = catIds.indexOf(sel)
        if (i > 0) catAdapter.select(i)
    }

    private fun toggleFavorite(pos: Int) {
        val c = chAdapter.items.getOrNull(pos) ?: return
        showItemMenu(this, prefs, c) {
            if (isCollection) {
                refreshCollection()
                rvChannels.post {
                    val p = pos.coerceAtMost(chAdapter.itemCount - 1)
                    if (p >= 0) rvChannels.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus()
                    else focusSelectedCategory()
                }
            } else {
                chAdapter.notifyItemChanged(pos)
            }
        }
    }
}
