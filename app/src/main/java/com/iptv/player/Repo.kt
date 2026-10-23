package com.iptv.player

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Net {
    const val UA = "IPTVPlayer/1.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sunucu hatası (HTTP ${r.code})")
            r.body?.string() ?: ""
        }
    }
}

/** Yüklenen liste uygulama açıkken burada tutulur. */
object Repo {
    var account: Account? = null
    var categories: List<Category> = emptyList()
    var channels: List<Channel> = emptyList()

    /** Oynatıcıda ▲▼ ile gezilen liste (o an ekranda olan kanallar) */
    var playlist: List<Channel> = emptyList()
    var lastIndex: Int = -1

    /** "ornek.com:8080/" gibi girişleri "http://ornek.com:8080" yapar */
    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        s = s.substringBefore("/player_api.php").substringBefore("/get.php")
        return s.trimEnd('/')
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(a: Account, action: String?, extra: String = "") = buildString {
        append(a.server).append("/player_api.php?username=").append(enc(a.username))
        append("&password=").append(enc(a.password))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    suspend fun checkXtream(a: Account) {
        val text = Net.get(api(a, null))
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("Sunucu Xtream yanıtı vermedi. Adresi ve portu kontrol et.")
        }
        val info = json.optJSONObject("user_info")
            ?: throw IOException("Sunucu Xtream yanıtı vermedi. Adresi ve portu kontrol et.")
        if (info.optInt("auth", 0) != 1) throw IOException("Kullanıcı adı veya şifre hatalı.")
        val status = info.optString("status", "")
        if (status.isNotEmpty() && !status.equals("Active", true)) {
            throw IOException("Hesap durumu: $status")
        }
    }

    suspend fun checkM3u(a: Account) {
        val text = Net.get(a.m3uUrl)
        if (!text.contains("#EXTM3U") && !text.contains("#EXTINF")) {
            throw IOException("Bu link bir M3U listesi değil.")
        }
    }

    suspend fun load(a: Account) = withContext(Dispatchers.Default) {
        if (a.isXtream) loadXtream(a) else loadM3u(a)
        account = a
    }

    private suspend fun loadXtream(a: Account) {
        val catArr = JSONArray(Net.get(api(a, "get_live_categories")))
        val cats = ArrayList<Category>(catArr.length())
        for (i in 0 until catArr.length()) {
            val o = catArr.getJSONObject(i)
            cats.add(Category(o.optString("category_id"), o.optString("category_name").trim()))
        }
        val arr = JSONArray(Net.get(api(a, "get_live_streams")))
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            if (id.isEmpty()) continue
            list.add(
                Channel(
                    key = "x:$id",
                    streamId = id,
                    name = o.optString("name").trim(),
                    logo = o.optString("stream_icon").trim().ifBlank { null },
                    categoryId = o.optString("category_id"),
                    url = "${a.server}/live/${a.username}/${a.password}/$id.ts",
                )
            )
        }
        if (list.isEmpty()) throw IOException("Hesapta canlı kanal bulunamadı.")
        categories = cats
        channels = list
    }

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    private suspend fun loadM3u(a: Account) {
        val text = Net.get(a.m3uUrl)
        val cats = LinkedHashMap<String, Category>()
        val list = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group = "Diğer"
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.trim()?.ifBlank { null }
                    group = attrs["group-title"]?.trim()?.ifBlank { null } ?: "Diğer"
                    name = nameAfterComma(line)?.ifBlank { null } ?: attrs["tvg-name"]
                }
                line.startsWith("#EXTGRP:", true) -> {
                    group = line.substringAfter(":").trim().ifBlank { group }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val g = group
                    cats.getOrPut(g) { Category(g, g) }
                    list.add(
                        Channel(
                            key = "m:$line",
                            streamId = list.size.toString(),
                            name = name ?: "Kanal ${list.size + 1}",
                            logo = logo,
                            categoryId = g,
                            url = line,
                        )
                    )
                    name = null
                    logo = null
                    group = "Diğer"
                }
            }
        }
        if (list.isEmpty()) throw IOException("Listede kanal bulunamadı.")
        categories = cats.values.toList()
        channels = list
    }

    /** #EXTINF satırında tırnak dışındaki ilk virgülden sonrası kanal adıdır */
    private fun nameAfterComma(line: String): String? {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
        }
        return null
    }

    suspend fun shortEpg(a: Account, streamId: String): List<EpgItem> {
        val o = JSONObject(Net.get(api(a, "get_short_epg", "&stream_id=$streamId&limit=2")))
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgItem>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            out.add(
                EpgItem(
                    b64(e.optString("title")),
                    e.optLong("start_timestamp") * 1000,
                    e.optLong("stop_timestamp") * 1000,
                )
            )
        }
        return out
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        s
    }
}
