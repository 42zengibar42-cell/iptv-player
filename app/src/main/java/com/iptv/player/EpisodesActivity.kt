package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

/** Bir dizinin sezonları (solda) ve bölümleri (sağda) */
class EpisodesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var seasonAdapter: CategoryAdapter
    private lateinit var episodeAdapter: ChannelAdapter
    private var seasons: List<Pair<String, List<Channel>>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episodes)
        prefs = Prefs(this)
        val account = prefs.account
        val seriesId = intent.getStringExtra("seriesId")
        if (account == null || seriesId == null) {
            finish()
            return
        }
        findViewById<TextView>(R.id.tvTitle).text = intent.getStringExtra("name") ?: ""
        rvSeasons = findViewById(R.id.rvSeasons)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tvEmpty)

        seasonAdapter = CategoryAdapter { showSeason(it) }
        rvSeasons.layoutManager = LinearLayoutManager(this)
        rvSeasons.itemAnimator = null
        rvSeasons.adapter = seasonAdapter

        episodeAdapter = ChannelAdapter(
            onClick = ::play,
            onLongClick = ::toggleFavorite,
            onLeft = { rvSeasons.findViewHolderForAdapterPosition(seasonAdapter.selected)?.itemView?.requestFocus() },
            badge = { prefs.badge(it.key) },
        )
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.itemAnimator = null
        rvEpisodes.adapter = episodeAdapter

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (details, eps) = Repo.loadEpisodes(account, seriesId)
                seasons = eps.toList()
                showDetails(details)
                if (seasons.isEmpty()) {
                    tvEmpty.text = "Bu dizide bölüm bulunamadı."
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    seasonAdapter.items = seasons.map { (s, eps) -> "Sezon $s (${eps.size})" }
                    seasonAdapter.selected = 0
                    seasonAdapter.notifyDataSetChanged()
                    // "İzlemeye devam et"ten gelindiyse o bölümün sezonunu aç ve bölümü başlat
                    val playKey = intent.getStringExtra("playKey")
                    intent.removeExtra("playKey")
                    val si = seasons.indexOfFirst { (_, eps) -> eps.any { it.key == playKey } }
                    val season = if (si >= 0) si else firstUnfinishedSeason()
                    seasonAdapter.selected = season
                    seasonAdapter.notifyDataSetChanged()
                    showSeason(season)
                    val ep = if (si >= 0) episodeAdapter.items.indexOfFirst { it.key == playKey }
                    else episodeAdapter.items.indexOfFirst { !prefs.isWatched(it.key) }.coerceAtLeast(0)
                    rvSeasons.scrollToPosition(season)
                    rvEpisodes.scrollToPosition(ep.coerceAtLeast(0))
                    rvEpisodes.post { rvEpisodes.findViewHolderForAdapterPosition(ep.coerceAtLeast(0))?.itemView?.requestFocus() }
                    if (si >= 0 && ep >= 0) play(ep)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                tvEmpty.text = "Bölümler yüklenemedi.\n${e.message ?: e.javaClass.simpleName}"
                tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::episodeAdapter.isInitialized) episodeAdapter.notifyDataSetChanged()
    }

    /** İzlenmemiş bölümü olan ilk sezon (hepsi izlendiyse ilk sezon) */
    private fun firstUnfinishedSeason(): Int =
        seasons.indexOfFirst { (_, eps) -> eps.any { !prefs.isWatched(it.key) } }.coerceAtLeast(0)

    private fun showSeason(i: Int) {
        episodeAdapter.items = seasons.getOrNull(i)?.second ?: emptyList()
        rvEpisodes.scrollToPosition(0)
    }

    private fun play(pos: Int) {
        val ep = episodeAdapter.items.getOrNull(pos) ?: return
        // Tüm sezonlar tek listede: bölüm bitince sonraki (sezon sonunda bir sonraki sezon) başlar
        val series = intent.getStringExtra("name") ?: ""
        val seriesId = intent.getStringExtra("seriesId") ?: ""
        val all = seasons.flatMap { (season, eps) ->
            eps.map { it.copy(name = "$series – S$season • ${it.name}", categoryId = "series:$seriesId") }
        }
        val start = all.indexOfFirst { it.key == ep.key }.coerceAtLeast(0)
        Repo.playlist = all
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", start).putExtra("vod", true))
    }

    private fun toggleFavorite(pos: Int) {
        val c = episodeAdapter.items.getOrNull(pos) ?: return
        showItemMenu(this, prefs, c) { episodeAdapter.notifyItemChanged(pos) }
    }

    /** Dizinin afişi, yıl / tür / puan ve konusu */
    private fun showDetails(d: Repo.Details?) {
        val poster = d?.poster ?: intent.getStringExtra("logo")
        if (poster != null) {
            val iv = findViewById<android.widget.ImageView>(R.id.ivPoster)
            iv.visibility = View.VISIBLE
            iv.load(poster)
        }
        if (d == null) return
        findViewById<TextView>(R.id.tvMeta).text = d.meta
        findViewById<TextView>(R.id.tvPlot).text = d.plot
    }
}
