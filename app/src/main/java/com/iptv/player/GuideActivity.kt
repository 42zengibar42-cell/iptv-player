package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TV rehberi: her satır bir kanal, programlar saat sırasıyla yan yana */
class GuideActivity : AppCompatActivity() {

    companion object {
        var channels: List<Channel> = emptyList()
        var title: String = ""

        /** 1 dakika = 4dp; 4 saatlik pencere */
        private const val DP_PER_MIN = 4f
        private const val WINDOW_MIN = 240
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val epg = HashMap<String, List<EpgItem>>()
    private val loadingIds = HashSet<String>()
    private lateinit var rv: RecyclerView

    /** Rehberin başladığı an: şimdiki zaman, yarım saate yuvarlanmış */
    private val origin: Long = System.currentTimeMillis().let { it - it % (30 * 60_000L) }
    private val windowEnd get() = origin + WINDOW_MIN * 60_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = Prefs(this).account
        if (channels.isEmpty() || account == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_guide)
        findViewById<TextView>(R.id.tvTitle).text =
            "📅 TV rehberi – $title   (${timeFmt.format(Date(origin))} – ${timeFmt.format(Date(windowEnd))})"
        rv = findViewById(R.id.rvGuide)
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.adapter = Adapter(account)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.ivLogo)
        val name: TextView = v.findViewById(R.id.tvName)
        val scroll: HorizontalScrollView = v.findViewById(R.id.scroll)
        val programs: LinearLayout = v.findViewById(R.id.programs)
    }

    private inner class Adapter(private val account: Account) : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = channels[position]
            h.name.text = "${position + 1}  ${c.name}"
            if (c.logo == null) h.logo.setImageResource(R.drawable.ic_tv) else h.logo.load(c.logo) { error(R.drawable.ic_tv) }
            h.programs.removeAllViews()
            h.scroll.scrollTo(0, 0)

            val list = epg[c.streamId]
            if (list == null) {
                addBlock(h, position, "Yükleniyor…", 200f, false)
                load(account, c, position)
                return
            }
            val now = System.currentTimeMillis()
            var x = origin
            var added = 0
            for (e in list) {
                val start = maxOf(e.start, origin)
                val end = minOf(e.end, windowEnd)
                if (end <= start) continue
                if (start > x) {
                    // Arada boşluk varsa boş alan bırak
                    val gap = View(this@GuideActivity)
                    h.programs.addView(gap, LinearLayout.LayoutParams(dp((start - x) / 60_000f * DP_PER_MIN), 1))
                }
                val mins = (end - start) / 60_000f
                val live = now in e.start until e.end
                addBlock(h, position, "${timeFmt.format(Date(e.start))}  ${e.title}", mins * DP_PER_MIN, live)
                x = end
                added++
            }
            if (added == 0) addBlock(h, position, "Yayın akışı yok", 300f, false)
        }

        private fun addBlock(h: VH, position: Int, text: String, widthDp: Float, live: Boolean) {
            val tv = TextView(this@GuideActivity).apply {
                this.text = text
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@GuideActivity, R.color.item_text))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), 0, dp(8f), 0)
                setBackgroundResource(R.drawable.bg_program)
                isActivated = live
                isFocusable = true
                setOnClickListener { openChannel(position) }
            }
            val lp = LinearLayout.LayoutParams(maxOf(dp(widthDp), dp(60f)), ViewGroup.LayoutParams.MATCH_PARENT)
            lp.rightMargin = dp(3f)
            h.programs.addView(tv, lp)
        }

        private fun load(account: Account, c: Channel, position: Int) {
            if (!loadingIds.add(c.streamId)) return
            lifecycleScope.launch {
                val list = try {
                    if (account.isXtream && c.key.startsWith("x:")) Repo.shortEpg(account, c.streamId, 12) else emptyList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                epg[c.streamId] = list
                loadingIds.remove(c.streamId)
                notifyItemChanged(position)
            }
        }
    }

    private fun openChannel(position: Int) {
        Repo.playlist = channels
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", position))
    }
}
