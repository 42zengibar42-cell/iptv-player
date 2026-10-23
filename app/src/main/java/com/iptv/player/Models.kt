package com.iptv.player

import android.content.Context

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

data class Channel(
    /** Favoriler için benzersiz anahtar */
    val key: String,
    /** Xtream stream_id (EPG için) */
    val streamId: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val url: String,
)

data class EpgItem(val title: String, val start: Long, val end: Long)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)

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

    fun favorites(): Set<String> = sp.getStringSet("fav", emptySet())?.toSet() ?: emptySet()

    /** Favoriye ekler ya da çıkarır. Eklendiyse true döner. */
    fun toggleFavorite(key: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (set.contains(key)) {
            set.remove(key); false
        } else {
            set.add(key); true
        }
        sp.edit().putStringSet("fav", set).apply()
        return added
    }
}
