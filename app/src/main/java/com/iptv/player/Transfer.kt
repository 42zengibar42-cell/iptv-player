package com.iptv.player

import android.content.Context

/**
 * Hesap bilgileri değişince (yenilenen abonelik: yeni kullanıcı adı / şifre) ayarları korur.
 * Favoriler, Listem, "izlemeye devam et" ve son kanal tam yayın adresini sakladığı için
 * içlerindeki eski sunucu / kullanıcı adı / şifre yenisiyle değiştirilir.
 */
object Transfer {

    private fun creds(a: Account): Triple<String, String, String>? {
        if (a.isXtream) return Triple(a.server, a.username, a.password)
        return try {
            val u = android.net.Uri.parse(a.m3uUrl)
            val user = u.getQueryParameter("username") ?: return null
            val pass = u.getQueryParameter("password") ?: return null
            val port = if (u.port > 0) ":${u.port}" else ""
            Triple("${u.scheme}://${u.host}$port", user, pass)
        } catch (e: Exception) {
            null
        }
    }

    private fun rewriter(old: Account, new: Account): ((String) -> String)? {
        val o = creds(old) ?: return null
        val n = creds(new) ?: return null
        if (o == n) return null
        return { s ->
            s.replace("/${o.second}/${o.third}/", "/${n.second}/${n.third}/")
                .replace(o.first, n.first)
        }
    }

    /** Aynı hesabın bilgileri değişti: kayıtlı adresleri güncelle */
    fun accountChanged(ctx: Context, id: String, old: Account, new: Account) {
        val fix = rewriter(old, new) ?: return
        val sp = ctx.applicationContext.getSharedPreferences(Prefs.dataFile(id), Context.MODE_PRIVATE)
        val e = sp.edit()
        for ((k, v) in sp.all) if (v is String) e.putString(k, fix(v))
        e.apply()
    }

    /** Bir hesabın tüm ayarlarını yeni hesaba kopyala (favoriler, sıralamalar, gizliler, kaldığın yerler) */
    fun copy(ctx: Context, from: Account, to: Account) {
        val app = ctx.applicationContext
        val fix = rewriter(from, to) ?: { s: String -> s }
        for ((src, dst) in listOf(
            Prefs.dataFile(from.id) to Prefs.dataFile(to.id),
            Prefs.posFile(from.id) to Prefs.posFile(to.id),
        )) {
            val s = app.getSharedPreferences(src, Context.MODE_PRIVATE)
            val e = app.getSharedPreferences(dst, Context.MODE_PRIVATE).edit().clear()
            for ((k, v) in s.all) {
                if (k == "update_check" || k == "expwarn_day") continue
                when (v) {
                    is String -> e.putString(k, fix(v))
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Float -> e.putFloat(k, v)
                    is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                }
            }
            e.commit()
        }
    }

    /** Aynı sağlayıcı mı (kanal numaraları aynı olur, favoriler doğru kanala gider) */
    fun sameServer(a: Account, b: Account): Boolean {
        val x = creds(a)?.first ?: return false
        val y = creds(b)?.first ?: return false
        return x.equals(y, ignoreCase = true)
    }
}
