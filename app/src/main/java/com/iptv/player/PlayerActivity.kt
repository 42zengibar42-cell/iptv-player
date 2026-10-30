package com.iptv.player

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
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
    private lateinit var tvHint: TextView
    private lateinit var tvNumber: TextView
    private lateinit var tvNext: TextView
    private lateinit var prefs: Prefs
    private lateinit var pipBox: View
    private lateinit var tvPipName: TextView
    private var pip: PreviewPlayer? = null
    /** true: ses köşedeki kanaldan gelir */
    private var pipAudio = false

    /** Film / dizi bölümü: sarma kontrolleri açık, ▲▼ kanal değiştirmez */
    private var vod = false

    private var player: ExoPlayer? = null
    private var index = 0
    private var started = false
    /** Son kanala dönüş için bir önceki kanal */
    private var prevIndex = -1
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
        tvHint = findViewById(R.id.tvHint)
        tvNumber = findViewById(R.id.tvNumber)
        tvNext = findViewById(R.id.tvNext)
        pipBox = findViewById(R.id.pipBox)
        tvPipName = findViewById(R.id.tvPipName)
        prefs = Prefs(this)
        vod = intent.getBooleanExtra("vod", false)
        if (vod) {
            playerView.useController = true
            playerView.controllerShowTimeoutMs = 4000
            playerView.isFocusable = true
            tvHint.text = "OK: oynat / duraklat   •   ◀ ▶ sar   •   ⚙ / CC: ses ve altyazı   •   OK basılı tut: görüntü ayarı"
        }

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
        startPip()
    }

    override fun onStop() {
        super.onStop()
        cancelNext()
        savePosition()
        player?.release()
        player = null
        pip?.release()
        pip = null
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
                if (state == Player.STATE_ENDED) onEnded()
            }
        })
        playerView.player = p
        applyAspect()
        player = p
    }

    private fun play(i: Int) {
        cancelNext()
        val n = Repo.playlist.size
        val newIndex = ((i % n) + n) % n
        if (started && newIndex != index) {
            savePosition()
            prevIndex = index
        }
        started = true
        index = newIndex
        Repo.lastIndex = index
        if (!vod) {
            Repo.liveList = Repo.playlist
            Repo.liveIndex = index
        }
        triedAlternative = false
        val c = Repo.playlist[index]
        start(c.url)
        if (vod) {
            val resume = prefs.position(c.key)
            if (resume > 0) {
                player?.seekTo(resume)
                Toast.makeText(this, "Kaldığın yerden devam ediliyor", Toast.LENGTH_SHORT).show()
            }
        }
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
        if (!vod && !triedAlternative && c.url.endsWith(".ts")) {
            triedAlternative = true
            start(c.url.removeSuffix(".ts") + ".m3u8")
            return
        }
        tvError.text = if (vod) "Bu içerik açılamadı.\n${e.errorCodeName}" else "Bu kanal açılamadı.\n${e.errorCodeName}\n\n▲ ▼ ile başka kanala geç"
        tvError.visibility = View.VISIBLE
    }

    private fun showInfo(c: Channel) {
        tvChName.text = if (vod) c.name else "${index + 1}   ${c.name}"
        infoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 6000)
        loadEpg(c)
    }

    private fun loadEpg(c: Channel) {
        epgJob?.cancel()
        tvEpgNow.text = ""
        tvEpgNext.text = ""
        if (vod || c.kind != Kind.LIVE) return
        val a = Repo.account ?: return
        if (!a.isXtream || !c.key.startsWith("x:")) return
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

    private fun savePosition() {
        if (!vod) return
        val p = player ?: return
        val c = Repo.playlist.getOrNull(index) ?: return
        if (p.duration > 0 && p.playbackState != Player.STATE_IDLE) {
            prefs.savePosition(c, p.currentPosition, p.duration)
        }
    }

    // ---------- Bitince: izlendi işareti ve sonraki bölüm ----------

    private var nextLeft = 0
    private val nextTick = object : Runnable {
        override fun run() {
            val next = Repo.playlist.getOrNull(index + 1) ?: return cancelNext()
            if (nextLeft <= 0) {
                play(index + 1)
                return
            }
            tvNext.text = "Sonraki bölüm:\n${next.name}\n\n$nextLeft sn içinde başlıyor\nOK: hemen başlat   •   Geri: çık"
            tvNext.visibility = View.VISIBLE
            nextLeft--
            handler.postDelayed(this, 1000)
        }
    }

    private val nextActive get() = tvNext.visibility == View.VISIBLE

    private fun cancelNext() {
        handler.removeCallbacks(nextTick)
        tvNext.visibility = View.GONE
    }

    private fun onEnded() {
        if (!vod) return
        val p = player
        val c = Repo.playlist.getOrNull(index) ?: return
        if (p != null && p.duration > 0) prefs.savePosition(c, p.duration, p.duration)
        if (index + 1 < Repo.playlist.size) {
            nextLeft = 10
            handler.removeCallbacks(nextTick)
            handler.post(nextTick)
        } else {
            finish()
        }
    }

    // ---------- Görüntü ayarı: OK'a basılı tut → Sığdır / Yakınlaştır / Ger ----------

    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val aspectNames = arrayOf(
        "Sığdır (orijinal)",
        "Yakınlaştır (tam ekran, kenarlar kesilir)",
        "Ger (tam ekran, görüntü yassılaşır)",
    )

    private fun applyAspect() {
        playerView.resizeMode = aspectModes[prefs.aspectMode.coerceIn(0, 2)]
    }

    private fun cycleAspect() {
        val next = (prefs.aspectMode + 1) % aspectModes.size
        prefs.aspectMode = next
        applyAspect()
        Toast.makeText(this, "Görüntü: ${aspectNames[next]}", Toast.LENGTH_SHORT).show()
    }

    // ---------- Köşedeki ikinci kanal ----------

    private val pipActive get() = pipBox.visibility == View.VISIBLE

    private fun startPip() {
        val c = Repo.pipChannel
        if (vod || c == null) {
            pipBox.visibility = View.GONE
            return
        }
        val p = pip ?: PreviewPlayer(this, findViewById(R.id.pipView)).also { pip = it }
        p.volume = if (pipAudio) 1f else 0f
        player?.volume = if (pipAudio) 0f else 1f
        p.play(c.url)
        tvPipName.text = (if (pipAudio) "🔊 " else "🔇 ") + c.name
        pipBox.visibility = View.VISIBLE
    }

    private fun closePip() {
        Repo.pipChannel = null
        pip?.release()
        pip = null
        pipAudio = false
        player?.volume = 1f
        pipBox.visibility = View.GONE
        Toast.makeText(this, "Köşedeki kanal kapatıldı", Toast.LENGTH_SHORT).show()
    }

    private fun toggleAudio() {
        pipAudio = !pipAudio
        startPip()
        Toast.makeText(this, if (pipAudio) "Ses: köşedeki kanal" else "Ses: ana kanal", Toast.LENGTH_SHORT).show()
    }

    /** Ana kanal köşeye, köşedeki kanal tam ekrana */
    private fun swapPip() {
        val corner = Repo.pipChannel ?: return
        val main = Repo.playlist.getOrNull(index) ?: return
        Repo.pipChannel = main
        var i = Repo.playlist.indexOfFirst { it.key == corner.key }
        if (i < 0) {
            Repo.playlist = Repo.playlist.toMutableList().apply { add(index + 1, corner) }
            i = index + 1
        }
        pip?.release()
        pip = null
        play(i)
        startPip()
    }

    private fun showPipMenu() {
        val items = arrayOf(
            "⇄  Yer değiştir",
            if (pipAudio) "🔊  Sesi ana kanala ver" else "🔊  Sesi köşedeki kanala ver",
            "✕  Köşedeki kanalı kapat",
            "💬  Ses ve altyazı",
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Köşede: ${Repo.pipChannel?.name ?: ""}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> swapPip()
                    1 -> toggleAudio()
                    2 -> closePip()
                    else -> showTrackDialog()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ---------- Ses ve altyazı seçimi ----------

    private fun langName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale("tr"))
        return if (name.isBlank() || name.equals(code, true)) code.uppercase()
        else name.replaceFirstChar { it.uppercase() }
    }

    private fun trackLabel(f: Format, i: Int, audio: Boolean): String {
        val parts = ArrayList<String>()
        langName(f.language)?.let { parts.add(it) }
        f.label?.takeIf { it.isNotBlank() && parts.none { p -> p.equals(it, true) } }?.let { parts.add(it) }
        if (audio && f.channelCount > 0) parts.add(if (f.channelCount >= 6) "5.1" else if (f.channelCount == 2) "Stereo" else "${f.channelCount} kanal")
        return if (parts.isEmpty()) "Parça ${i + 1}" else parts.joinToString(" • ")
    }

    private fun showTrackDialog() {
        val p = player ?: return
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        var hasText = false
        for (type in intArrayOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            for (g in p.currentTracks.groups) {
                if (g.type != type) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    if (type == C.TRACK_TYPE_TEXT) hasText = true
                    val selected = g.isTrackSelected(i)
                    names.add(
                        (if (type == C.TRACK_TYPE_AUDIO) "🔊  Ses: " else "💬  Altyazı: ") +
                            trackLabel(g.getTrackFormat(i), i, type == C.TRACK_TYPE_AUDIO) +
                            if (selected) "   ✓" else ""
                    )
                    val group = g.mediaTrackGroup
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(group, i))
                            .build()
                    }
                }
            }
        }
        if (hasText) {
            val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            names.add("💬  Altyazı: Kapalı" + if (off) "   ✓" else "")
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
        if (names.isEmpty()) {
            Toast.makeText(this, "Bu yayında seçilebilecek ses veya altyazı yok", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ses ve altyazı")
            .setItems(names.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ---------- Rakamla kanal açma ----------

    private val numBuffer = StringBuilder()
    private val commitNumber = Runnable {
        val n = numBuffer.toString().toIntOrNull() ?: 0
        numBuffer.clear()
        tvNumber.visibility = View.GONE
        if (n in 1..Repo.playlist.size) play(n - 1)
        else Toast.makeText(this, "$n numaralı kanal yok", Toast.LENGTH_SHORT).show()
    }

    private fun digitOf(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    private fun typeDigit(d: Int) {
        if (numBuffer.length >= 4) numBuffer.clear()
        numBuffer.append(d)
        tvNumber.text = numBuffer.toString()
        tvNumber.visibility = View.VISIBLE
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 1500)
    }

    // ---------- Tuşlar ----------

    /** OK tuşu: kısa basış normal davranır, uzun basış görüntü ayarını değiştirir */
    private var okDown: KeyEvent? = null
    private var okLongHandled = false

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isOk(code)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        okDown = KeyEvent(event)
                        okLongHandled = false
                    } else if (!okLongHandled && event.repeatCount >= 1) {
                        okLongHandled = true
                        cycleAspect()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val down = okDown
                    okDown = null
                    if (okLongHandled || down == null) return true
                    // Kısa basış
                    when {
                        nextActive -> play(index + 1)
                        vod -> {
                            infoPanel.visibility = View.GONE
                            playerView.dispatchKeyEvent(down)
                            playerView.dispatchKeyEvent(event)
                        }
                        else -> Repo.playlist.getOrNull(index)?.let { showInfo(it) }
                    }
                    return true
                }
            }
        }
        if (code == KeyEvent.KEYCODE_BACK && !vod && pipActive && !nextActive) {
            if (event.action == KeyEvent.ACTION_UP) closePip()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (code == KeyEvent.KEYCODE_CAPTIONS || code == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)
        ) {
            showTrackDialog()
            return true
        }
        if (vod && code != KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) infoPanel.visibility = View.GONE
            if (playerView.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (vod) return super.onKeyDown(keyCode, event)
        val d = digitOf(keyCode)
        if (d >= 0) {
            typeDigit(d)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                play(index - 1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                play(index + 1); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (prevIndex in Repo.playlist.indices) play(prevIndex)
                else Toast.makeText(this, "Henüz önceki kanal yok", Toast.LENGTH_SHORT).show()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pipActive) showPipMenu() else showTrackDialog()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                Repo.playlist.getOrNull(index)?.let { showInfo(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
