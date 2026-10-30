package com.iptv.player

import android.content.Context
import androidx.annotation.OptIn
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

/** Kanal listesindeki küçük önizleme penceresi */
@OptIn(UnstableApi::class)
class PreviewPlayer(private val context: Context, private val view: PlayerView) {

    private var player: ExoPlayer? = null

    /** 0 = sessiz, 1 = sesli */
    var volume: Float = 1f
        set(v) {
            field = v
            player?.volume = v
        }
    private var currentUrl: String? = null
    private var triedAlternative = false

    private fun create(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors))
            .build()
        p.playWhenReady = true
        p.volume = volume
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val url = currentUrl ?: return
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                if (!triedAlternative && url.endsWith(".ts")) {
                    triedAlternative = true
                    load(url.removeSuffix(".ts") + ".m3u8")
                }
            }
        })
        view.player = p
        player = p
        return p
    }

    fun play(url: String) {
        if (url == currentUrl && player != null) return
        currentUrl = url
        triedAlternative = false
        load(url)
    }

    private fun load(url: String) {
        val p = player ?: create()
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.prepare()
    }

    /** Bağlantıyı kapatır (tam ekran açılmadan önce: bazı sağlayıcılar tek bağlantıya izin verir) */
    fun release() {
        view.player = null
        player?.release()
        player = null
        currentUrl = null
    }
}
