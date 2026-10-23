package com.iptv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var infoPanel: LinearLayout
    private lateinit var tvChName: TextView
    private lateinit var tvEpgNow: TextView
    private lateinit var tvEpgNext: TextView
    private lateinit var tvError: TextView

    private var player: ExoPlayer? = null
    private var index = 0
    private var triedAlternative = false
    private var epgJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoPanel.visibility = View.GONE }
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        WindowCompat.getInsetsController(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = findViewById(R.id.playerView)
        infoPanel = findViewById(R.id.infoPanel)
        tvChName = findViewById(R.id.tvChName)
        tvEpgNow = findViewById(R.id.tvEpgNow)
        tvEpgNext = findViewById(R.id.tvEpgNext)
        tvError = findViewById(R.id.tvError)

        if (Repo.playlist.isEmpty()) {
            finish()
            return
        }
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        if (Repo.playlist.isEmpty()) return
        initPlayer()
        play(index)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .build()
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) tvError.visibility = View.GONE
            }
        })
        playerView.player = p
        player = p
    }

    private fun play(i: Int) {
        val n = Repo.playlist.size
        index = ((i % n) + n) % n
        Repo.lastIndex = index
        triedAlternative = false
        val c = Repo.playlist[index]
        start(c.url)
        showInfo(c)
    }

    private fun start(url: String) {
        tvError.visibility = View.GONE
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player?.run {
            setMediaItem(item)
            prepare()
        }
    }

    private fun handleError(e: PlaybackException) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.run {
                seekToDefaultPosition()
                prepare()
            }
            return
        }
        val c = Repo.playlist.getOrNull(index) ?: return
        // Xtream .ts açılmazsa aynı kanalı .m3u8 olarak dene
        if (!triedAlternative && c.url.endsWith(".ts")) {
            triedAlternative = true
            start(c.url.removeSuffix(".ts") + ".m3u8")
            return
        }
        tvError.text = "Bu kanal açılamadı.\n${e.errorCodeName}\n\n▲ ▼ ile başka kanala geç"
        tvError.visibility = View.VISIBLE
    }

    private fun showInfo(c: Channel) {
        tvChName.text = "${index + 1}   ${c.name}"
        infoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 6000)
        loadEpg(c)
    }

    private fun loadEpg(c: Channel) {
        epgJob?.cancel()
        tvEpgNow.text = ""
        tvEpgNext.text = ""
        val a = Repo.account ?: return
        if (!a.isXtream) return
        epgJob = lifecycleScope.launch {
            try {
                val list = Repo.shortEpg(a, c.streamId)
                list.getOrNull(0)?.let {
                    tvEpgNow.text = "Şimdi  ${timeFmt.format(Date(it.start))}–${timeFmt.format(Date(it.end))}   ${it.title}"
                }
                list.getOrNull(1)?.let {
                    tvEpgNext.text = "Sonra  ${timeFmt.format(Date(it.start))}   ${it.title}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Yayın akışı yoksa sessizce geç
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                play(index - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                play(index + 1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                Repo.playlist.getOrNull(index)?.let { showInfo(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
