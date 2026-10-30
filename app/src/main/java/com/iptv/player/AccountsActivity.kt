package com.iptv.player

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Kayıtlı hesaplar: geç, düzenle, sil, yeni ekle */
class AccountsActivity : AppCompatActivity() {

    private lateinit var adapter: CategoryAdapter
    private var accounts: List<Account> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        val rv = findViewById<RecyclerView>(R.id.rvAccounts)
        adapter = CategoryAdapter(onClick = ::onRow) { }
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.adapter = adapter
        findViewById<TextView>(R.id.tvVersion).text = "KcKTV sürüm ${Updater.currentVersion(this)}"
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { Updater.check(this, manual = true) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        accounts = Accounts.all(this)
        val active = Accounts.activeId(this)
        adapter.items = accounts.map { a ->
            val kind = if (a.isXtream) "Xtream • ${a.username}" else "M3U"
            (if (a.id == active) "✓  " else "     ") + "${a.title}   ($kind)"
        } + "＋  Yeni hesap ekle"
        adapter.selected = -1
        adapter.notifyDataSetChanged()
        findViewById<RecyclerView>(R.id.rvAccounts).post {
            findViewById<RecyclerView>(R.id.rvAccounts).findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun onRow(pos: Int) {
        val a = accounts.getOrNull(pos)
        if (a == null) {
            startActivity(Intent(this, LoginActivity::class.java).putExtra("new", true))
            return
        }
        val active = a.id == Accounts.activeId(this)
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (!active) {
            items.add("▶  Bu hesaba geç"); actions.add { switchTo(a.id) }
        }
        items.add("✎  Düzenle"); actions.add {
            startActivity(Intent(this, LoginActivity::class.java).putExtra("accountId", a.id))
        }
        items.add("🗑  Sil"); actions.add { confirmDelete(a) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.title + if (active) "  (açık)" else "")
            .setItems(items.toTypedArray()) { _, w -> actions[w]() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun switchTo(id: String) {
        Accounts.setActive(this, id)
        Repo.account = null
        Repo.sections.clear()
        Repo.pipChannel = null
        startActivity(
            Intent(this, ChannelsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun confirmDelete(a: Account) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${a.title} silinsin mi?")
            .setMessage("Bu hesabın favorileri, Listem'i ve kategori ayarları da silinir.")
            .setPositiveButton("Sil") { _, _ ->
                val wasActive = a.id == Accounts.activeId(this)
                Accounts.remove(this, a.id)
                Toast.makeText(this, "Silindi: ${a.title}", Toast.LENGTH_SHORT).show()
                val next = Accounts.all(this).firstOrNull()
                when {
                    next == null -> {
                        Repo.account = null
                        startActivity(
                            Intent(this, LoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                    wasActive -> switchTo(next.id)
                    else -> refresh()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
}
