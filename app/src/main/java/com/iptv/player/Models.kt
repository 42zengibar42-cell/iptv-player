package com.iptv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Account(
    val type: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    val m3uUrl: String,
) {
    val isXtream get() = type == TYPE_XTREAM

    companion object {
        const val TYPE_XTREAM = "xtream"
        const val TYPE_M3U = "m3u"
    }
}

data class Category(val id: String, val name: String)

object Kind {
    const val LIVE = "live"
    const val MOVIE = "movie"
    /** Xtream dizisi: tıklanınca sezon/bölüm ekranı açılır (url boş) */
    const val SERIES = "series"
    /** Doğrudan oynatılabilen dizi bölümü */
    const val EPISODE = "episode"
}

data class Channel(
    /** Favoriler ve kaldığın yer için benzersiz anahtar */
    val key: String,
    /** Xtream stream_id / series_id */
    val streamId: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val url: String,
    val kind: String = Kind.LIVE,
    /** Sağlayıcıya eklenme zamanı (sn), "En yeni" sıralaması için */
    val added: Long = 0,
) {
    val isVod get() = kind != Kind.LIVE
    val isSeriesFolder get() = kind == Kind.SERIES && url.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("id", streamId).put("name", name)
        .put("logo", logo ?: "").put("cat", categoryId).put("url", url).put("kind", kind)

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            key = o.optString("key"),
            streamId = o.optString("id"),
            name = o.optString("name"),
            logo = o.optString("logo").ifBlank { null },
            categoryId = o.optString("cat"),
            url = o.optString("url"),
            kind = o.optString("kind", Kind.LIVE),
        )
    }
}

data class EpgItem(val title: String, val start: Long, val end: Long)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val pos = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val type = sp.getString("type", null) ?: return null
            return Account(
                type,
                sp.getString("name", "") ?: "",
                sp.getString("server", "") ?: "",
                sp.getString("user", "") ?: "",
                sp.getString("pass", "") ?: "",
                sp.getString("m3u", "") ?: "",
            )
        }
        set(v) {
            val e = sp.edit()
            if (v == null) {
                listOf("type", "name", "server", "user", "pass", "m3u").forEach { e.remove(it) }
            } else {
                e.putString("type", v.type)
                    .putString("name", v.name)
                    .putString("server", v.server)
                    .putString("user", v.username)
                    .putString("pass", v.password)
                    .putString("m3u", v.m3uUrl)
            }
            e.apply()
        }

    // ---------- Favoriler ve Listem ----------

    companion object {
        const val FAV = "favs"
        const val LIST = "mylist"
    }

    private val cache = HashMap<String, MutableList<Channel>>()

    fun items(col: String): List<Channel> {
        cache[col]?.let { return it }
        val list = ArrayList<Channel>()
        try {
            val arr = JSONArray(sp.getString(col, "[]"))
            for (i in 0 until arr.length()) list.add(Channel.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) {
        }
        cache[col] = list
        return list
    }

    fun contains(col: String, key: String) = items(col).any { it.key == key }

    /** Ekler ya da çıkarır. Eklendiyse true döner. */
    fun toggle(col: String, c: Channel): Boolean {
        val list = items(col).toMutableList()
        val i = list.indexOfFirst { it.key == c.key }
        val added = if (i >= 0) {
            list.removeAt(i); false
        } else {
            list.add(0, c); true
        }
        save(col, list)
        return added
    }

    private fun save(col: String, list: MutableList<Channel>) {
        cache[col] = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(col, arr.toString()).apply()
    }

    /** Listede yanında görünen işaretler */
    fun badge(key: String): String = buildString {
        if (contains(LIST, key)) append("☰")
        if (contains(FAV, key)) {
            if (isNotEmpty()) append("  ")
            append("★")
        }
    }

    /** İlk sürümde favoriler sadece anahtar olarak saklanıyordu; kanallar yüklenince taşı */
    fun migrateOldFavorites(live: List<Channel>) {
        val old = sp.getStringSet("fav", null) ?: return
        val list = items(FAV).toMutableList()
        live.filter { old.contains(it.key) && list.none { f -> f.key == it.key } }.forEach { list.add(it) }
        save(FAV, list)
        sp.edit().remove("fav").apply()
    }

    // ---------- Gizli kategoriler ve son seçilen kategori ----------

    fun hiddenCategories(section: String): Set<String> =
        sp.getStringSet("hidden_$section", emptySet())?.toSet() ?: emptySet()

    fun setCategoryHidden(section: String, id: String, hidden: Boolean) {
        val set = hiddenCategories(section).toMutableSet()
        if (hidden) set.add(id) else set.remove(id)
        sp.edit().putStringSet("hidden_$section", set).apply()
    }

    fun clearHiddenCategories(section: String) {
        sp.edit().remove("hidden_$section").apply()
    }

    /** Kullanıcının belirlediği kategori sırası (kategori kimlikleri) */
    fun categoryOrder(section: String): List<String> = try {
        val arr = JSONArray(sp.getString("order_$section", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun setCategoryOrder(section: String, ids: List<String>) {
        sp.edit().putString("order_$section", JSONArray(ids).toString()).apply()
    }

    /** Kayıtlı sıraya göre dizer; yeni gelen kategoriler sona eklenir */
    fun sortCategories(section: String, cats: List<Category>): List<Category> {
        val order = categoryOrder(section)
        if (order.isEmpty()) return cats
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, id -> rank[id] = i }
        return cats.withIndex()
            .sortedWith(compareBy({ rank[it.value.id] ?: (order.size + it.index) }))
            .map { it.value }
    }

    /** Gizli kategoriler için 4 haneli şifre (yoksa null) */
    var pin: String?
        get() = sp.getString("pin", null)?.ifBlank { null }
        set(v) {
            sp.edit().apply { if (v == null) remove("pin") else putString("pin", v) }.apply()
        }

    /** 0 = sağlayıcı sırası, 1 = A–Z, 2 = en yeni */
    fun sortMode(section: String): Int = sp.getInt("sort_$section", 0)

    fun setSortMode(section: String, mode: Int) {
        sp.edit().putInt("sort_$section", mode).apply()
    }

    fun lastCategory(section: String): String? = sp.getString("lastcat_$section", null)

    fun setLastCategory(section: String, id: String) {
        sp.edit().putString("lastcat_$section", id).apply()
    }

    // ---------- Kaldığın yer (filmler / bölümler) ----------

    fun position(key: String): Long = pos.getLong(key, 0L)

    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        val e = pos.edit()
        // İlk 30 sn ve son 3 dk kaydedilmez (bitti sayılır)
        if (positionMs < 30_000 || (durationMs > 0 && durationMs - positionMs < 180_000)) {
            e.remove(key)
        } else {
            e.putLong(key, positionMs)
        }
        e.apply()
    }
}
