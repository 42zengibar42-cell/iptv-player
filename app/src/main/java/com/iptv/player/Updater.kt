package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** GitHub'daki son sürümü kontrol eder, indirir ve kurulumu başlatır */
object Updater {
    private const val API = "https://api.github.com/repos/42zengibar42-cell/iptv-player/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Release(val version: Long, val apkUrl: String)

    fun currentVersion(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API)
            .header("User-Agent", Net.UA)
            .header("Accept", "application/vnd.github+json")
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val o = JSONObject(r.body?.string() ?: return@withContext null)
            val version = o.optString("tag_name").removePrefix("v").toLongOrNull() ?: return@withContext null
            val assets = o.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext Release(version, a.optString("browser_download_url"))
                }
            }
            null
        }
    }

    /** Açılışta sessiz kontrol (6 saatte bir) */
    fun <A> autoCheck(activity: A) where A : Activity, A : LifecycleOwner {
        val sp = activity.getSharedPreferences("iptv", Context.MODE_PRIVATE)
        val last = sp.getLong("update_check", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        sp.edit().putLong("update_check", System.currentTimeMillis()).apply()
        check(activity, manual = false)
    }

    /** manual = true: "Güncel" / hata mesajlarını da göster */
    fun <A> check(activity: A, manual: Boolean) where A : Activity, A : LifecycleOwner {
        activity.lifecycleScope.launch {
            val rel = try {
                latest()
            } catch (e: Exception) {
                null
            }
            if (activity.isFinishing) return@launch
            val current = currentVersion(activity)
            when {
                rel == null -> if (manual) toast(activity, "Güncelleme bilgisi alınamadı. İnternet bağlantısını kontrol et (GitHub deposu gizliyse kontrol çalışmaz).")
                rel.version <= current -> if (manual) toast(activity, "Uygulama güncel (sürüm $current)")
                else -> AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Yeni sürüm var")
                    .setMessage("KcKTV sürüm ${rel.version} hazır (şu an: $current).\nAyarların ve favorilerin silinmez.\n\nŞimdi güncellensin mi?")
                    .setPositiveButton("Güncelle") { _, _ -> download(activity, rel) }
                    .setNegativeButton("Sonra", null)
                    .show()
            }
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    private fun <A> download(activity: A, rel: Release) where A : Activity, A : LifecycleOwner {
        // Android 8+: "bilinmeyen uygulamaları yükle" izni gerekli
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "KcKTV için \"Bilinmeyen uygulamaları yükle\" iznini aç, sonra geri dönüp tekrar Güncelle'ye bas.")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (e: Exception) {
            }
            return
        }
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Güncelleme indiriliyor")
            .setMessage("Lütfen bekle… %0")
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            try {
                val dir = if (Build.VERSION.SDK_INT >= 24) activity.cacheDir else (activity.externalCacheDir ?: activity.cacheDir)
                val file = File(dir, "update.apk")
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(rel.apkUrl).header("User-Agent", Net.UA).build()
                    Net.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        val body = r.body ?: throw IOException("Boş yanıt")
                        val total = body.contentLength()
                        var done = 0L
                        var lastPct = -1
                        body.byteStream().use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            withContext(Dispatchers.Main) { dialog.setMessage("Lütfen bekle… %$pct") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                dialog.dismiss()
                install(activity, file)
            } catch (e: Exception) {
                dialog.dismiss()
                toast(activity, "İndirilemedi: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        } else {
            file.setReadable(true, false)
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
