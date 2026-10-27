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
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sunucu hatası (HTTP ${r.code})")
            r.body?.string() ?: ""
        }
    }
}

class SectionData(val categories: List<Category>, val items: List<Channel>)

/** Yüklenen listeler uygulama açıkken burada tutulur. */
object Repo {
    var account: Account? = null
        set(v) {
            if (v != field) sections.clear()
            field = v
        }

    /** Kind.LIVE / Kind.MOVIE / Kind.SERIES → yüklenmiş içerik */
    val sections = HashMap<String, SectionData>()

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

    /** İstenen bölümü yükler (önbellekte varsa ve force değilse tekrar indirmez) */
    suspend fun loadSection(a: Account, kind: String, force: Boolean = false): SectionData =
        withContext(Dispatchers.Default) {
            account = a
            if (!force) sections[kind]?.let { return@withContext it }
            if (a.isXtream) {
                val data = when (kind) {
                    Kind.LIVE -> loadXtreamLive(a)
                    Kind.MOVIE -> loadXtreamMovies(a)
                    else -> loadXtreamSeries(a)
                }
                sections[kind] = data
            } else {
                loadM3u(a)
            }
            sections[kind] ?: SectionData(emptyList(), emptyList())
        }

    private fun cats(arr: JSONArray): List<Category> {
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Category(o.optString("category_id"), o.optString("category_name").trim()))
        }
        return out
    }

    private fun arrayOrEmpty(text: String): JSONArray = try {
        JSONArray(text)
    } catch (e: Exception) {
        JSONArray()
    }

    private suspend fun loadXtreamLive(a: Account): SectionData {
        val categories = cats(arrayOrEmpty(Net.get(api(a, "get_live_categories"))))
        val arr = arrayOrEmpty(Net.get(api(a, "get_live_streams")))
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
                    kind = Kind.LIVE,
                )
            )
        }
        if (list.isEmpty()) throw IOException("Hesapta canlı kanal bulunamadı.")
        return SectionData(categories, list)
    }

    private suspend fun loadXtreamMovies(a: Account): SectionData {
        val categories = cats(arrayOrEmpty(Net.get(api(a, "get_vod_categories"))))
        val arr = arrayOrEmpty(Net.get(api(a, "get_vod_streams")))
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            if (id.isEmpty()) continue
            val ext = o.optString("container_extension").ifBlank { "mp4" }
            list.add(
                Channel(
                    key = "v:$id",
                    streamId = id,
                    name = o.optString("name").trim(),
                    logo = o.optString("stream_icon").trim().ifBlank { null },
                    categoryId = o.optString("category_id"),
                    url = "${a.server}/movie/${a.username}/${a.password}/$id.$ext",
                    kind = Kind.MOVIE,
                )
            )
        }
        return SectionData(categories, list)
    }

    private suspend fun loadXtreamSeries(a: Account): SectionData {
        val categories = cats(arrayOrEmpty(Net.get(api(a, "get_series_categories"))))
        val arr = arrayOrEmpty(Net.get(api(a, "get_series")))
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("series_id")
            if (id.isEmpty()) continue
            list.add(
                Channel(
                    key = "s:$id",
                    streamId = id,
                    name = o.optString("name").trim(),
                    logo = o.optString("cover").trim().ifBlank { null },
                    categoryId = o.optString("category_id"),
                    url = "",
                    kind = Kind.SERIES,
                )
            )
        }
        return SectionData(categories, list)
    }

    /** Film / dizi açıklaması */
    data class Details(
        val poster: String?,
        val plot: String,
        val genre: String,
        val year: String,
        val duration: String,
        val rating: String,
        val cast: String,
        val director: String,
    ) {
        /** "2023 • Dram, Gerilim • 2 sa 5 dk • ★ 7.4" */
        val meta: String
            get() = listOf(year, genre, duration, if (rating.isNotBlank()) "★ $rating" else "")
                .filter { it.isNotBlank() }.joinToString("   •   ")
    }

    private fun JSONObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = opt(k)
            val t = when (v) {
                null, JSONObject.NULL -> ""
                is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
                else -> v.toString()
            }.trim()
            if (t.isNotEmpty() && t != "null" && t != "0" && t != "0.0") return t
        }
        return ""
    }

    private fun parseDetails(info: JSONObject?): Details? {
        if (info == null) return null
        var year = info.str("releasedate", "releaseDate", "release_date", "year")
        if (year.length >= 4 && year[0].isDigit()) year = year.take(4)
        var duration = info.str("duration")
        // "01:45:00" → "1 sa 45 dk"
        Regex("""^(\d+):(\d+)(:\d+)?$""").find(duration)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            duration = if (h > 0) "$h sa $m dk" else "$m dk"
        }
        if (duration.isEmpty()) {
            val secs = info.optLong("duration_secs", 0)
            if (secs > 0) duration = "${secs / 3600} sa ${(secs % 3600) / 60} dk"
        }
        val rating = info.str("rating", "rating_5based").let { r ->
            r.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: r
        }
        return Details(
            poster = info.str("movie_image", "cover_big", "cover", "backdrop_path").ifBlank { null },
            plot = info.str("plot", "description"),
            genre = info.str("genre"),
            year = year,
            duration = duration,
            rating = rating,
            cast = info.str("cast", "actors"),
            director = info.str("director"),
        )
    }

    suspend fun movieDetails(a: Account, vodId: String): Details? = withContext(Dispatchers.Default) {
        val o = JSONObject(Net.get(api(a, "get_vod_info", "&vod_id=$vodId")))
        parseDetails(o.optJSONObject("info"))
    }

    /** Bir dizinin açıklaması ve bölümleri (sezon numarası → bölümler) */
    suspend fun loadEpisodes(a: Account, seriesId: String): Pair<Details?, LinkedHashMap<String, List<Channel>>> =
        withContext(Dispatchers.Default) {
            val o = JSONObject(Net.get(api(a, "get_series_info", "&series_id=$seriesId")))
            val details = parseDetails(o.optJSONObject("info"))
            val out = LinkedHashMap<String, List<Channel>>()
            val eps = o.opt("episodes")
            fun parse(season: String, arr: JSONArray) {
                val list = ArrayList<Channel>()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id")
                    if (id.isEmpty()) continue
                    val ext = e.optString("container_extension").ifBlank { "mp4" }
                    val num = e.optString("episode_num")
                    val title = e.optString("title").trim()
                    list.add(
                        Channel(
                            key = "e:$id",
                            streamId = id,
                            name = if (num.isNotEmpty()) "$num. bölüm – $title" else title,
                            logo = e.optJSONObject("info")?.optString("movie_image")?.trim()?.ifBlank { null },
                            categoryId = season,
                            url = "${a.server}/series/${a.username}/${a.password}/$id.$ext",
                            kind = Kind.EPISODE,
                        )
                    )
                }
                if (list.isNotEmpty()) out[season] = list
            }
            when (eps) {
                is JSONObject -> {
                    val keys = eps.keys().asSequence().toList()
                        .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                    for (k in keys) eps.optJSONArray(k)?.let { parse(k, it) }
                }
                is JSONArray -> for (i in 0 until eps.length()) {
                    eps.optJSONArray(i)?.let { parse((i + 1).toString(), it) }
                }
            }
            details to out
        }

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    /** M3U listesini okur; linke göre canlı / film / dizi olarak ayırır */
    private suspend fun loadM3u(a: Account) {
        val text = Net.get(a.m3uUrl)
        val catMaps = mapOf(
            Kind.LIVE to LinkedHashMap<String, Category>(),
            Kind.MOVIE to LinkedHashMap(),
            Kind.SERIES to LinkedHashMap(),
        )
        val lists = mapOf(
            Kind.LIVE to ArrayList<Channel>(),
            Kind.MOVIE to ArrayList(),
            Kind.SERIES to ArrayList(),
        )
        var name: String? = null
        var logo: String? = null
        var group = "Diğer"
        var n = 0
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
                    val section = when {
                        line.contains("/movie/") -> Kind.MOVIE
                        line.contains("/series/") -> Kind.SERIES
                        else -> Kind.LIVE
                    }
                    val g = group
                    catMaps.getValue(section).getOrPut(g) { Category(g, g) }
                    n++
                    lists.getValue(section).add(
                        Channel(
                            key = "m:$line",
                            streamId = n.toString(),
                            name = name ?: "Kanal $n",
                            logo = logo,
                            categoryId = g,
                            url = line,
                            kind = if (section == Kind.SERIES) Kind.EPISODE else section,
                        )
                    )
                    name = null
                    logo = null
                    group = "Diğer"
                }
            }
        }
        if (n == 0) throw IOException("Listede kanal bulunamadı.")
        for (k in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) {
            sections[k] = SectionData(catMaps.getValue(k).values.toList(), lists.getValue(k))
        }
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
