package com.iptv.player

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/** OK'a basılı tutunca açılan menü: Favorilere / Listeme ekle-çıkar */
fun showItemMenu(activity: Activity, prefs: Prefs, c: Channel, onChanged: () -> Unit) {
    val fav = prefs.contains(Prefs.FAV, c.key)
    val inList = prefs.contains(Prefs.LIST, c.key)
    val options = arrayOf(
        if (fav) "★  Favorilerden çıkar" else "★  Favorilere ekle",
        if (inList) "☰  Listemden çıkar" else "☰  Listeme ekle",
    )
    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(c.name)
        .setItems(options) { _, which ->
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
