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
    if (live) {
        options.add("📺  Köşede aç (ikinci kanal)")
        options.add("▥  Yan yana aç (ikinci kanal)")
    }
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(c.name)
        .setItems(options.toTypedArray()) { _, which ->
            if (which >= 2) {
                openSecond(activity, prefs, c, split = which == 3)
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

/** Aynı hesaptan iki yayın açılacaksa abonelik tek bağlantılıysa uyarır */
fun checkConnections(activity: Activity, acc: Account?, go: () -> Unit) {
    if (acc == null || !acc.isXtream) return go()
    val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return go()
    owner.lifecycleScope.launch {
        val max = Repo.loadMaxConnections(acc)
        if (max != null && max < 2) {
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Bu abonelik tek bağlantılı")
                .setMessage("\"${acc.title}\" aynı anda sadece $max yayına izin veriyor. İkinci kanal açılmayabilir ya da ana kanal donabilir.\n\nİpucu: ikinci kanalı diğer aboneliğinden seçebilirsin (oynatıcıda ▶ → Başka hesaptan kanal seç).\n\nYine de denensin mi?")
                .setPositiveButton("Dene") { _, _ -> go() }
                .setNegativeButton("Vazgeç", null)
                .show()
        } else {
            go()
        }
    }
}

/** Seçilen kanalı köşeye / yan yana koyar ve son izlenen kanalı tam ekranda açar */
fun openSecond(activity: Activity, prefs: Prefs, c: Channel, split: Boolean) {
    val main = Repo.liveList.getOrNull(Repo.liveIndex)
    Repo.splitMode = split
    if (main == null || main.key == c.key) {
        Repo.pipChannel = c
        Toast.makeText(activity, "Şimdi ana kanalı aç; ${c.name} ${if (split) "yanında" else "köşede"} oynayacak.", Toast.LENGTH_LONG).show()
        return
    }
    checkConnections(activity, prefs.account) {
        Repo.pipChannel = c
        Repo.playlist = Repo.liveList
        activity.startActivity(
            android.content.Intent(activity, PlayerActivity::class.java).putExtra("index", Repo.liveIndex)
        )
    }
}

/**
 * İkinci kanalı seçtirir: hesap (birden fazlaysa) → kategori → kanal.
 * Farklı hesaptan seçilen kanal o hesabın bağlantısını kullanır.
 */
fun pickSecondChannel(activity: Activity, onPicked: (Channel, Account) -> Unit) {
    val accounts = Accounts.all(activity)
    if (accounts.size <= 1) {
        accounts.firstOrNull()?.let { pickFromAccount(activity, it, onPicked) }
        return
    }
    val activeId = Accounts.activeId(activity)
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Hangi hesaptan?")
        .setItems(accounts.map { it.title + if (it.id == activeId) "  (şu an açık)" else "" }.toTypedArray()) { _, w ->
            pickFromAccount(activity, accounts[w], onPicked)
        }
        .setNegativeButton("Vazgeç", null)
        .show()
}

private fun pickFromAccount(activity: Activity, acc: Account, onPicked: (Channel, Account) -> Unit) {
    val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return
    Toast.makeText(activity, "${acc.title}: kanallar yükleniyor…", Toast.LENGTH_SHORT).show()
    owner.lifecycleScope.launch {
        val data = try {
            Repo.liveOf(acc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(activity, "Yüklenemedi: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return@launch
        }
        val p = Prefs(activity, acc.id)
        val hidden = p.hiddenCategories(Kind.LIVE)
        val counts = data.items.groupingBy { it.categoryId }.eachCount()
        val cats = p.sortCategories(Kind.LIVE, data.categories).filter { it.id !in hidden && counts.containsKey(it.id) }
        val favs = p.items(Prefs.FAV).filter { it.kind == Kind.LIVE }
        val names = ArrayList<String>()
        val lists = ArrayList<List<Channel>>()
        if (favs.isNotEmpty()) {
            names.add("★ Favoriler (${favs.size})"); lists.add(favs)
        }
        for (c in cats) {
            names.add("${c.name} (${counts[c.id]})")
            lists.add(data.items.filter { it.categoryId == c.id })
        }
        if (names.isEmpty()) {
            names.add("Tümü (${data.items.size})"); lists.add(data.items)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${acc.title}: kategori seç")
            .setItems(names.toTypedArray()) { _, w ->
                val chans = lists[w]
                AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(names[w])
                    .setItems(chans.mapIndexed { i, c -> "${i + 1}   ${c.name}" }.toTypedArray()) { _, ci ->
                        onPicked(chans[ci], acc)
                    }
                    .setNegativeButton("Geri", null)
                    .show()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
