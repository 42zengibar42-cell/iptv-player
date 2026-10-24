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
                seasons = Repo.loadEpisodes(account, seriesId).toList()
                if (seasons.isEmpty()) {
                    tvEmpty.text = "Bu dizide bölüm bulunamadı."
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    seasonAdapter.items = seasons.map { (s, eps) -> "Sezon $s (${eps.size})" }
                    seasonAdapter.selected = 0
                    seasonAdapter.notifyDataSetChanged()
                    showSeason(0)
                    rvEpisodes.post { rvEpisodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
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

    private fun showSeason(i: Int) {
        episodeAdapter.items = seasons.getOrNull(i)?.second ?: emptyList()
        rvEpisodes.scrollToPosition(0)
    }

    private fun play(pos: Int) {
        if (pos < 0) return
        Repo.playlist = episodeAdapter.items
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", pos).putExtra("vod", true))
    }

    private fun toggleFavorite(pos: Int) {
        val c = episodeAdapter.items.getOrNull(pos) ?: return
        showItemMenu(this, prefs, c) { episodeAdapter.notifyItemChanged(pos) }
    }
}
