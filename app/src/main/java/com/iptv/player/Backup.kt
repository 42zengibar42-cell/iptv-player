package com.iptv.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tüm ayarları tek JSON dosyasına yazar / geri yükler:
 * hesaplar, her hesabın favorileri, Listem'i, sıralamaları, gizli kategorileri, kaldığın yerler.
 */
object Backup {
    const val PREFIX = "KcKTV-yedek"

    fun fileName(): String = "$PREFIX-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".json"

    private fun files(ctx: Context): List<String> {
        val ids = Accounts.all(ctx).map { it.id }
        return listOf("accounts") + ids.flatMap { listOf(Prefs.dataFile(it), Prefs.posFile(it)) }
    }

    fun export(ctx: Context): String {
        Accounts.all(ctx) // eski kurulumu taşı
        val root = JSONObject().put("app", "KcKTV").put("version", 1)
            .put("created", System.currentTimeMillis())
        val prefs = JSONObject()
        for (name in files(ctx)) {
            val sp = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val o = JSONObject()
            for ((k, v) in sp.all) {
                if (k == "update_check") continue
                val e = JSONObject()
                when (v) {
                    is String -> e.put("t", "s").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, e)
            }
            prefs.put(name, o)
        }
        return root.put("prefs", prefs).toString(1)
    }

    /** Yedeği yükler; mevcut ayarların yerine geçer. Yüklenen hesap sayısını döner. */
    fun import(ctx: Context, text: String): Int {
        val root = JSONObject(text)
        require(root.optString("app") == "KcKTV") { "Bu bir KcKTV yedek dosyası değil." }
        val prefs = root.getJSONObject("prefs")
        val app = ctx.applicationContext
        // Önce mevcut hesapların verilerini temizle
        for (name in files(ctx)) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        for (name in prefs.keys()) {
            if (name != "accounts" && !name.startsWith("iptv") && !name.startsWith("positions")) continue
            val o = prefs.getJSONObject(name)
            val e = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val x = o.getJSONObject(k)
                when (x.optString("t")) {
                    "s" -> e.putString(k, x.getString("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "set" -> {
                        val arr = x.getJSONArray("v")
                        e.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toSet())
                    }
                }
            }
            e.commit()
        }
        Repo.account = null
        Repo.sections.clear()
        Repo.pipChannel = null
        return Accounts.all(ctx).size
    }

    /** Dosya seçici olmayan kutular için: İndirilenler (Download) klasörüne kaydet */
    fun saveToDownloads(ctx: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Dosya oluşturulamadı")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeText(text)
        }
        return "İndirilenler (Download) klasörü / $name"
    }

    /** İndirilenler klasöründeki KcKTV yedekleri (en yenisi önce) */
    fun findInDownloads(ctx: Context): List<Pair<String, Uri>> {
        val out = ArrayList<Pair<String, Uri>>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$PREFIX%"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        out.add(c.getString(1) to Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.listFiles { f -> f.name.startsWith(PREFIX) }?.sortedByDescending { it.lastModified() }
                    ?.forEach { out.add(it.name to Uri.fromFile(it)) }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun read(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Dosya okunamadı")
}
