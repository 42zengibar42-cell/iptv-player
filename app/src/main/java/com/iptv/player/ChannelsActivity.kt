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
        const val CONT = "__cont__"
        const val GUIDE = "__guide__"
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
    private lateinit var previewPanel: LinearLayout
    private lateinit var tvPreviewName: TextView
    private lateinit var tvPreviewNow: TextView
    private lateinit var tvPreviewNext: TextView
    private var preview: PreviewPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var previewEpgJob: Job? = null
    private var searchJob: Job? = null
    private var lastFocusedPos = -1
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

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
        previewPanel = findViewById(R.id.previewPanel)
        tvPreviewName = findViewById(R.id.tvPreviewName)
        tvPreviewNow = findViewById(R.id.tvPreviewNow)
        tvPreviewNext = findViewById(R.id.tvPreviewNext)
        preview = PreviewPlayer(this, findViewById(R.id.previewView))

        catAdapter = CategoryAdapter(onLongClick = ::categoryMenu, onClick = ::categoryClick) { applyFilter() }
        rvCategories.layoutManager = LinearLayoutManager(this)
        rvCategories.itemAnimator = null
        rvCategories.adapter = catAdapter

        chAdapter = ChannelAdapter(
            onClick = ::openItem,
            onLongClick = ::toggleFavorite,
            onLeft = ::focusSelectedCategory,
            badge = { prefs.badge(it.key) },
            onFocus = ::onChannelFocus,
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
        findViewById<Button>(R.id.btnAccount).apply {
            // Birden fazla hesap varsa açık olanın adı görünsün
            if (Accounts.all(this@ChannelsActivity).size > 1) text = "⚙ " + account.title.take(12)
            setOnClickListener { startActivity(Intent(this@ChannelsActivity, AccountsActivity::class.java)) }
        }

        showTab(Kind.LIVE, focusList = true)
        Updater.autoCheck(this)
    }

    override fun onStop() {
        super.onStop()
        stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------- Kanal önizleme ----------

    private val startPreview = Runnable {
        val c = chAdapter.items.getOrNull(lastFocusedPos) ?: return@Runnable
        if (c.kind != Kind.LIVE || previewPanel.visibility != View.VISIBLE) return@Runnable
        preview?.play(c.url)
        tvPreviewName.text = c.name
        tvPreviewNow.text = ""
        tvPreviewNext.text = ""
        previewEpgJob?.cancel()
        if (account.isXtream && c.key.startsWith("x:")) {
            previewEpgJob = lifecycleScope.launch {
                try {
                    val list = Repo.shortEpg(account, c.streamId)
                    list.getOrNull(0)?.let {
                        tvPreviewNow.text = "Şimdi ${timeFmt.format(java.util.Date(it.start))}  ${it.title}"
                    }
                    list.getOrNull(1)?.let {
                        tvPreviewNext.text = "Sonra ${timeFmt.format(java.util.Date(it.start))}  ${it.title}"
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun onChannelFocus(pos: Int) {
        lastFocusedPos = pos
        if (previewPanel.visibility != View.VISIBLE) return
        handler.removeCallbacks(startPreview)
        handler.postDelayed(startPreview, 700)
    }

    private fun stopPreview() {
        handler.removeCallbacks(startPreview)
        previewEpgJob?.cancel()
        preview?.release()
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
        if (isCollection) {
            refreshCollection()
        } else if ((tab == Kind.MOVIE || tab == Kind.SERIES) && etSearch.text.isBlank()) {
            // "İzlemeye devam et" listesi değişmiş olabilir
            val pos = lastFocusedPos
            rebuildSection(focus = false)
            if (pos >= 0) rvChannels.post {
                val p = pos.coerceAtMost(chAdapter.itemCount - 1)
                rvChannels.scrollToPosition(p.coerceAtLeast(0))
                rvChannels.post { rvChannels.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
            }
            return
        } else {
            chAdapter.notifyDataSetChanged()
        }
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
        stopPreview()
        previewPanel.visibility = if (key == Kind.LIVE) View.VISIBLE else View.GONE
        tvPreviewName.text = "Bir kanalın üzerine gel, burada önizlemesi oynar."
        tvPreviewNow.text = ""
        tvPreviewNext.text = ""
        lastFocusedPos = -1
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
        if (!isCollection) {
            val n = continueItems().size
            if (n > 0) {
                ids.add(CONT); titles.add("▶ İzlemeye devam et ($n)")
            }
        }
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
            if (tab == Kind.LIVE && account.isXtream) {
                ids.add(GUIDE); titles.add("📅 TV rehberi")
            }
            ids.add(EDIT)
            titles.add(
                when {
                    prefs.pin != null -> "✎ Kategorileri düzenle 🔒"
                    hiddenCount > 0 -> "✎ Kategorileri düzenle ($hiddenCount gizli)"
                    else -> "✎ Kategorileri düzenle"
                }
            )
        }
        catIds = ids
        catAdapter.items = titles
        val last = if (isCollection) -1 else ids.indexOf(prefs.lastCategory(tab))
        catAdapter.selected = when {
            last >= 0 && ids[last] != EDIT && ids[last] != GUIDE -> last
            else -> ids.indexOf(ALL).coerceAtLeast(0)
        }
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
        // 2+ harf: tüm bölümlerde (canlı, film, dizi) ara
        if (q.length >= 2 && !isCollection) {
            ensureAllLoaded()
            chAdapter.showKind = true
            chAdapter.items = sorted(searchAll(q))
            rvChannels.scrollToPosition(0)
            showEmptyIfNeeded()
            return
        }
        chAdapter.showKind = false
        val id = catIds.getOrElse(catAdapter.selected) { ALL }
        if (!isCollection && id != EDIT && id != GUIDE) prefs.setLastCategory(tab, id)
        if (id == CONT) {
            chAdapter.items = continueItems()
            rvChannels.scrollToPosition(0)
            showEmptyIfNeeded()
            return
        }
        var list = when {
            (id == EDIT || id == GUIDE) && q.isEmpty() -> emptyList()
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
            q.length >= 2 && searchJob?.isActive == true -> "Aranıyor… (film ve dizi listeleri ilk seferde yükleniyor)"
            q.isNotEmpty() -> "\"$q\" için sonuç bulunamadı."
            catIds.getOrNull(catAdapter.selected) == GUIDE -> "OK'a bas: son seçtiğin kategorinin TV rehberini aç."
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
        stopPreview()
        when {
            c.kind == Kind.EPISODE && c.categoryId.startsWith("series:") && account.isXtream -> startActivity(
                // Yarım kalan bölüm: dizi ekranını aç ve bölümü oradan başlat (sonraki bölüm de çalışsın)
                Intent(this, EpisodesActivity::class.java)
                    .putExtra("seriesId", c.categoryId.removePrefix("series:"))
                    .putExtra("name", c.name.substringBefore(" – S"))
                    .putExtra("playKey", c.key)
            )
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
                val live = if (items.all { it.kind == Kind.LIVE }) items else items.filter { it.kind == Kind.LIVE }
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
    private fun rebuildSection(focus: Boolean = true) {
        val data = Repo.sections[tab] ?: return
        setSection(data)
        buildCategories(focusList = focus, visibleCategories())
    }

    /** Bu bölümde yarım kalan filmler / bölümler */
    private fun continueItems(): List<Channel> = when (tab) {
        Kind.MOVIE -> prefs.items(Prefs.CONT).filter { it.kind == Kind.MOVIE }
        Kind.SERIES -> prefs.items(Prefs.CONT).filter { it.kind == Kind.EPISODE }
        else -> emptyList()
    }

    // ---------- Genel arama ----------

    private fun searchAll(q: String): List<Channel> =
        listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES).flatMap { k ->
            val d = Repo.sections[k] ?: return@flatMap emptyList<Channel>()
            val hidden = prefs.hiddenCategories(k)
            d.items.filter { it.categoryId !in hidden && it.name.contains(q, ignoreCase = true) }
        }

    /** Aramada film / dizi listeleri henüz yüklenmediyse arka planda yükle */
    private fun ensureAllLoaded() {
        if (searchJob?.isActive == true) return
        val missing = listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES).filter { Repo.sections[it] == null }
        if (missing.isEmpty()) return
        searchJob = lifecycleScope.launch {
            for (k in missing) {
                try {
                    Repo.loadSection(account, k)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }
            if (etSearch.text.toString().trim().length >= 2) applyFilter()
        }
    }

    // ---------- TV rehberi ----------

    private fun openGuide() {
        val id = prefs.lastCategory(Kind.LIVE)
        val list = if (id == null || id == ALL || id == CONT) sectionItems
        else sectionItems.filter { it.categoryId == id }.ifEmpty { sectionItems }
        if (list.isEmpty()) return
        stopPreview()
        GuideActivity.channels = list
        GuideActivity.title = sectionCategories.firstOrNull { it.id == id }?.name ?: "Tümü"
        startActivity(Intent(this, GuideActivity::class.java))
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
        if (catIds.getOrNull(pos) == GUIDE) {
            openGuide()
            return
        }
        if (catIds.getOrNull(pos) != EDIT || Repo.sections[tab] == null) return
        val section = tab
        askPin(this, prefs) {
            editorOpened = true
            startActivity(Intent(this, CategoryEditActivity::class.java).putExtra("section", section))
        }
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
