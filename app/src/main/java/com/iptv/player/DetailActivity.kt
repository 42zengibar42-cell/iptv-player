package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

/** Film bilgi ekranı: afiş, konu, yıl, tür, süre, puan, oyuncular */
class DetailActivity : AppCompatActivity() {

    companion object {
        /** Açılacak film (Intent ile taşımak yerine) */
        var item: Channel? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var c: Channel
    private lateinit var btnPlay: Button
    private lateinit var btnRestart: Button
    private lateinit var btnFav: Button
    private lateinit var btnList: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = item
        if (it == null) {
            finish()
            return
        }
        c = it
        prefs = Prefs(this)
        setContentView(R.layout.activity_detail)

        val ivPoster = findViewById<ImageView>(R.id.ivPoster)
        val tvMeta = findViewById<TextView>(R.id.tvMeta)
        val tvPlot = findViewById<TextView>(R.id.tvPlot)
        val tvCast = findViewById<TextView>(R.id.tvCast)
        val progress = findViewById<ProgressBar>(R.id.progress)
        btnPlay = findViewById(R.id.btnPlay)
        btnRestart = findViewById(R.id.btnRestart)
        btnFav = findViewById(R.id.btnFav)
        btnList = findViewById(R.id.btnList)

        findViewById<TextView>(R.id.tvTitle).text = c.name
        loadPoster(ivPoster, c.logo)

        btnPlay.setOnClickListener { play(fromStart = false) }
        btnRestart.setOnClickListener { play(fromStart = true) }
        btnFav.setOnClickListener { toggle(Prefs.FAV) }
        btnList.setOnClickListener { toggle(Prefs.LIST) }
        updateButtons()
        btnPlay.requestFocus()

        val account = prefs.account
        if (account == null || !account.isXtream || !c.key.startsWith("v:")) {
            tvPlot.text = "Bu film için açıklama yok."
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val d = Repo.movieDetails(account, c.streamId)
                if (d == null) {
                    tvPlot.text = "Bu film için açıklama yok."
                } else {
                    tvMeta.text = d.meta
                    tvPlot.text = d.plot.ifBlank { "Bu film için açıklama yok." }
                    tvCast.text = buildString {
                        if (d.director.isNotBlank()) append("Yönetmen: ").append(d.director)
                        if (d.cast.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append("Oyuncular: ").append(d.cast)
                        }
                    }
                    if (c.logo == null && d.poster != null) loadPoster(ivPoster, d.poster)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                tvPlot.text = "Açıklama yüklenemedi."
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::btnPlay.isInitialized) updateButtons()
    }

    private fun loadPoster(iv: ImageView, url: String?) {
        if (url == null) {
            iv.setImageResource(R.drawable.ic_tv)
            iv.scaleType = ImageView.ScaleType.CENTER
        } else {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.load(url) { error(R.drawable.ic_tv) }
        }
    }

    private fun updateButtons() {
        val pos = prefs.position(c.key)
        if (pos > 0) {
            btnPlay.text = "▶ Devam et (${formatTime(pos)})"
            btnRestart.visibility = View.VISIBLE
        } else {
            btnPlay.text = "▶ İzle"
            btnRestart.visibility = View.GONE
        }
        btnFav.text = if (prefs.contains(Prefs.FAV, c.key)) "★ Favorilerde" else "☆ Favorilere ekle"
        btnList.text = if (prefs.contains(Prefs.LIST, c.key)) "☰ Listemde" else "☰ Listeme ekle"
    }

    private fun toggle(col: String) {
        val added = prefs.toggle(col, c)
        val where = if (col == Prefs.FAV) "Favoriler" else "Listem"
        Toast.makeText(this, if (added) "$where'e eklendi" else "$where'den çıkarıldı", Toast.LENGTH_SHORT).show()
        updateButtons()
    }

    private fun play(fromStart: Boolean) {
        if (fromStart) prefs.resetPosition(c.key)
        Repo.playlist = listOf(c)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", 0).putExtra("vod", true))
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }
}
