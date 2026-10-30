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
    val id: String = "",
) {
    val isXtream get() = type == TYPE_XTREAM

    /** Listede görünen ad */
    val title: String
        get() = name.ifBlank { if (isXtream) username else "M3U listesi" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("name", name).put("server", server)
        .put("user", username).put("pass", password).put("m3u", m3uUrl)

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

/** Kayıtlı hesaplar ve hangisinin açık olduğu */
object Accounts {
    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    /** İlk sürümlerde tek hesap "iptv" dosyasında duruyordu: onu 1 numaralı hesap yap */
    private fun migrate(ctx: Context) {
        val st = store(ctx)
        if (st.contains("list")) return
        val old = ctx.applicationContext.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        val arr = JSONArray()
        val type = old.getString("type", null)
        if (type != null) {
            arr.put(
                Account(
                    type,
                    old.getString("name", "") ?: "",
                    old.getString("server", "") ?: "",
                    old.getString("user", "") ?: "",
                    old.getString("pass", "") ?: "",
                    old.getString("m3u", "") ?: "",
                    id = "1",
                ).toJson()
            )
            st.edit().putString("active", "1").apply()
        }
        st.edit().putString("list", arr.toString()).apply()
    }

    fun all(ctx: Context): List<Account> {
        migrate(ctx)
        val out = ArrayList<Account>()
        try {
            val arr = JSONArray(store(ctx).getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Account(
                        o.optString("type"), o.optString("name"), o.optString("server"),
                        o.optString("user"), o.optString("pass"), o.optString("m3u"), o.optString("id"),
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun get(ctx: Context, id: String?): Account? = all(ctx).firstOrNull { it.id == id }

    fun activeId(ctx: Context): String? {
        val list = all(ctx)
        val id = store(ctx).getString("active", null)
        return if (list.any { it.id == id }) id else list.firstOrNull()?.id
    }

    fun active(ctx: Context): Account? = get(ctx, activeId(ctx))

    fun setActive(ctx: Context, id: String) {
        store(ctx).edit().putString("active", id).apply()
    }

    fun newId(ctx: Context): String =
        ((all(ctx).mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()

    fun save(ctx: Context, a: Account) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list.add(a)
        write(ctx, list)
    }

    /** Hesabı ve ona ait favori / liste / ayarları siler */
    fun remove(ctx: Context, id: String) {
        write(ctx, all(ctx).filter { it.id != id })
        val app = ctx.applicationContext
        app.getSharedPreferences(Prefs.dataFile(id), Context.MODE_PRIVATE).edit().clear().apply()
        app.getSharedPreferences(Prefs.posFile(id), Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun write(ctx: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        store(ctx).edit().putString("list", arr.toString()).apply()
    }
}

/** Bir hesabın ayarları; her hesabın favorileri, listesi ve kategori ayarları ayrıdır */
class Prefs(context: Context, accountId: String? = null) {
    private val app = context.applicationContext
    val accountId: String = accountId ?: Accounts.activeId(app) ?: "1"
    private val sp = app.getSharedPreferences(dataFile(this.accountId), Context.MODE_PRIVATE)
    private val pos = app.getSharedPreferences(posFile(this.accountId), Context.MODE_PRIVATE)

    val account: Account?
        get() = Accounts.get(app, accountId)

    // ---------- Favoriler ve Listem ----------

    companion object {
        /** 1 numaralı hesap eski dosya adlarını kullanır (güncellemede veriler kaybolmasın) */
        fun dataFile(id: String) = if (id == "1") "iptv" else "iptv_$id"
        fun posFile(id: String) = if (id == "1") "positions" else "positions_$id"

        const val FAV = "favs"
        const val LIST = "mylist"
        /** Yarım kalan filmler / bölümler */
        const val CONT = "continue"
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
        if (isWatched(key)) append("✓")
        if (contains(LIST, key)) {
            if (isNotEmpty()) append("  ")
            append("☰")
        }
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

    /** Görüntü: 0 = sığdır, 1 = yakınlaştır, 2 = ger */
    var aspectMode: Int
        get() = sp.getInt("aspect", 0)
        set(v) {
            sp.edit().putInt("aspect", v).apply()
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

    // ---------- Kaldığın yer, izlemeye devam et, izlendi ----------

    fun position(key: String): Long = pos.getLong(key, 0L)

    fun resetPosition(key: String) {
        pos.edit().remove(key).apply()
        removeFrom(CONT, key)
    }

    /**
     * Film / bölüm konumunu kaydeder.
     * İlk 30 sn kaydedilmez; %90'ı ya da son 3 dk'sı izlendiyse "izlendi" sayılır.
     */
    fun savePosition(c: Channel, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 &&
            (durationMs - positionMs < 180_000 || positionMs >= durationMs * 0.9)
        when {
            finished -> {
                pos.edit().remove(c.key).apply()
                removeFrom(CONT, c.key)
                setWatched(c.key)
            }
            positionMs < 30_000 -> Unit
            else -> {
                pos.edit().putLong(c.key, positionMs).apply()
                val list = items(CONT).filter { it.key != c.key }.toMutableList()
                list.add(0, c)
                save(CONT, list.take(40).toMutableList())
            }
        }
    }

    private fun removeFrom(col: String, key: String) {
        val list = items(col)
        if (list.any { it.key == key }) save(col, list.filter { it.key != key }.toMutableList())
    }

    private var watchedCache: MutableSet<String>? = null

    private fun watched(): MutableSet<String> =
        watchedCache ?: (sp.getStringSet("watched", emptySet())?.toMutableSet() ?: HashSet()).also { watchedCache = it }

    fun isWatched(key: String) = watched().contains(key)

    fun setWatched(key: String) {
        val set = watched()
        if (set.add(key)) sp.edit().putStringSet("watched", HashSet(set)).apply()
    }
}
