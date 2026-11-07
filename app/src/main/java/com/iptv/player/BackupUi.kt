package com.iptv.player

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Yedekle / Geri yükle düğmelerinin işleri. Activity'nin onCreate'inde oluşturulmalı.
 * Önce sistemin dosya seçicisi denenir (USB bellek de seçilebilir);
 * kutuda yoksa İndirilenler (Download) klasörü kullanılır.
 */
class BackupUi(private val a: AppCompatActivity) {

    private val createDoc = a.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeTo(uri)
    }

    private val openDoc = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    private var pendingAfterPermission: (() -> Unit)? = null
    private val permission = a.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val next = pendingAfterPermission
        pendingAfterPermission = null
        if (ok) next?.invoke() else toast("Depolama izni verilmedi")
    }

    private fun toast(msg: String) = Toast.makeText(a, msg, Toast.LENGTH_LONG).show()

    /** Android 9 ve altında İndirilenler klasörü için izin gerekir */
    private fun withStorage(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(a, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            block()
        } else {
            pendingAfterPermission = block
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun backup() {
        if (Accounts.all(a).isEmpty()) {
            toast("Yedeklenecek hesap yok")
            return
        }
        try {
            createDoc.launch(Backup.fileName())
        } catch (e: ActivityNotFoundException) {
            backupToDownloads()
        }
    }

    private fun backupToDownloads() = withStorage {
        try {
            val where = Backup.saveToDownloads(a, Backup.fileName(), Backup.export(a))
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("💾 Yedek kaydedildi")
                .setMessage("$where\n\nYeni kutuya geçerken bu dosyayı USB belleğe kopyala.")
                .setPositiveButton("Tamam", null)
                .show()
        } catch (e: Exception) {
            toast("Yedeklenemedi: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun writeTo(uri: Uri) {
        try {
            a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(Backup.export(a).toByteArray()) }
                ?: throw IllegalStateException("Dosya açılamadı")
            toast("💾 Yedek kaydedildi")
        } catch (e: Exception) {
            toast("Yedeklenemedi: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun restore() {
        try {
            openDoc.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            restoreFromDownloads()
        }
    }

    private fun restoreFromDownloads() = withStorage {
        val found = Backup.findInDownloads(a)
        if (found.isEmpty()) {
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Yedek bulunamadı")
                .setMessage("İndirilenler (Download) klasöründe KcKTV yedeği yok.\n\nYedek dosyasını (adı \"${Backup.PREFIX}…\" ile başlar) kutunun Download klasörüne kopyalayıp tekrar dene.")
                .setPositiveButton("Tamam", null)
                .show()
            return@withStorage
        }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Hangi yedek?")
            .setItems(found.map { it.first }.toTypedArray()) { _, w -> confirmRestore(found[w].second) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun confirmRestore(uri: Uri) {
        val text = try {
            Backup.read(a, uri)
        } catch (e: Exception) {
            toast("Dosya okunamadı: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val doIt = {
            try {
                val n = Backup.import(a, text)
                toast("📂 Yedek yüklendi: $n hesap")
                a.startActivity(
                    Intent(a, ChannelsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                a.finish()
            } catch (e: Exception) {
                toast("Yüklenemedi: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (Accounts.all(a).isEmpty()) {
            doIt()
            return
        }
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Yedek yüklensin mi?")
            .setMessage("Şu anki hesapların ve ayarların yedektekilerle değiştirilecek.")
            .setPositiveButton("Yükle") { _, _ -> doIt() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
