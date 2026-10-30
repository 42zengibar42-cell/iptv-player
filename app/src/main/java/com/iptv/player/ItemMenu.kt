package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** OK'a basılı tutunca açılan menü: Favorilere / Listeme ekle-çıkar */
fun showItemMenu(activity: Activity, prefs: Prefs, c: Channel, onChanged: () -> Unit) {
    val fav = prefs.contains(Prefs.FAV, c.key)
    val inList = prefs.contains(Prefs.LIST, c.key)
    val options = arrayListOf(
        if (fav) "★  Favorilerden çıkar" else "★  Favorilere ekle",
        if (inList) "☰  Listemden çıkar" else "☰  Listeme ekle",
    )
    val live = c.kind == Kind.LIVE
    if (live) options.add("📺  Köşede aç (ikinci kanal)")
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(c.name)
        .setItems(options.toTypedArray()) { _, which ->
            if (which == 2) {
                openInCorner(activity, prefs, c)
                return@setItems
            }
            val col = if (which == 0) Prefs.FAV else Prefs.LIST
            val added = prefs.toggle(col, c)
            val where = if (col == Prefs.FAV) "Favoriler" else "Listem"
            Toast.makeText(
                activity,
                if (added) "$where'e eklendi" else "$where'den çıkarıldı",
                Toast.LENGTH_SHORT,
            ).show()
            onChanged()
        }
        .setNegativeButton("Kapat", null)
        .show()
}

private fun pinInput(activity: Activity): EditText = EditText(activity).apply {
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    filters = arrayOf(android.text.InputFilter.LengthFilter(4))
    hint = "••••"
    textSize = 24f
    gravity = android.view.Gravity.CENTER
}

private fun AlertDialog.showWithKeyboard(input: EditText) {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    show()
    input.requestFocus()
}

/** Şifre sorar; doğruysa onOk çalışır. Şifre yoksa doğrudan çalışır. */
fun askPin(activity: Activity, prefs: Prefs, onOk: () -> Unit) {
    val pin = prefs.pin ?: return onOk()
    val input = pinInput(activity)
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("🔒 Şifreyi gir")
        .setView(input)
        .setPositiveButton("Aç") { _, _ ->
            if (input.text.toString() == pin) onOk()
            else Toast.makeText(activity, "Şifre yanlış", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Vazgeç", null)
        .setNeutralButton("Unuttum") { _, _ -> forgotPin(activity, prefs) }
        .create()
        .showWithKeyboard(input)
}

/** Şifre unutulursa IPTV hesabının şifresiyle sıfırlanır */
private fun forgotPin(activity: Activity, prefs: Prefs) {
    val acc = prefs.account
    if (acc == null || !acc.isXtream || acc.password.isEmpty()) {
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Şifre sıfırlama")
            .setMessage("Bu hesapta sıfırlama yapılamıyor. Kutunun ayarlarından uygulamanın verilerini temizlemen gerekir (tüm ayarlar silinir).")
            .setPositiveButton("Tamam", null)
            .show()
        return
    }
    val input = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "IPTV hesabının şifresi"
    }
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Şifreyi sıfırla")
        .setMessage("Sağlayıcının sana verdiği IPTV hesap şifresini gir. Doğruysa kategori şifresi kaldırılır.")
        .setView(input)
        .setPositiveButton("Sıfırla") { _, _ ->
            if (input.text.toString() == acc.password) {
                prefs.pin = null
                Toast.makeText(activity, "Kategori şifresi kaldırıldı", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, "Hesap şifresi yanlış", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Vazgeç", null)
        .create()
        .showWithKeyboard(input)
}

/** Yeni 4 haneli şifre belirler */
fun setNewPin(activity: Activity, prefs: Prefs, onDone: () -> Unit) {
    val input = pinInput(activity)
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("🔒 4 haneli şifre belirle")
        .setMessage("Gizli kategorileri görmek ve geri açmak için bu şifre sorulacak.")
        .setView(input)
        .setPositiveButton("Kaydet") { _, _ ->
            val v = input.text.toString()
            if (v.length == 4 && v.all { it.isDigit() }) {
                prefs.pin = v
                Toast.makeText(activity, "Şifre kaydedildi: unutma!", Toast.LENGTH_LONG).show()
                onDone()
            } else {
                Toast.makeText(activity, "Şifre 4 rakam olmalı", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Vazgeç", null)
        .create()
        .showWithKeyboard(input)
}

/** Seçilen kanalı köşeye koyar ve son izlenen kanalı tam ekranda açar */
fun openInCorner(activity: Activity, prefs: Prefs, c: Channel) {
    val main = Repo.liveList.getOrNull(Repo.liveIndex)
    if (main == null || main.key == c.key) {
        Repo.pipChannel = c
        Toast.makeText(activity, "Şimdi tam ekranda izlemek istediğin kanalı aç; ${c.name} köşede oynayacak.", Toast.LENGTH_LONG).show()
        return
    }
    fun go() {
        Repo.pipChannel = c
        Repo.playlist = Repo.liveList
        activity.startActivity(
            android.content.Intent(activity, PlayerActivity::class.java).putExtra("index", Repo.liveIndex)
        )
    }
    val acc = prefs.account
    if (acc == null || !acc.isXtream) return go()
    (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
        val max = Repo.loadMaxConnections(acc)
        if (max != null && max < 2) {
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Aboneliğin tek bağlantılı")
                .setMessage("Sağlayıcın aynı anda sadece $max yayına izin veriyor. İkinci kanal açılmayabilir ya da ana kanal donabilir.\n\nYine de denensin mi?")
                .setPositiveButton("Dene") { _, _ -> go() }
                .setNegativeButton("Vazgeç", null)
                .show()
        } else {
            go()
        }
    } ?: go()
}
