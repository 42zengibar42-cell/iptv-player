package com.iptv.player

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Kayıtlı hesaplar: geç, düzenle, sil, yeni ekle */
class AccountsActivity : AppCompatActivity() {

    private lateinit var adapter: CategoryAdapter
    private var accounts: List<Account> = emptyList()
    private val infos = HashMap<String, String>()
    private lateinit var backupUi: BackupUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        val rv = findViewById<RecyclerView>(R.id.rvAccounts)
        adapter = CategoryAdapter(onClick = ::onRow) { }
        adapter.multiLine = true
        rv.layoutManager = LinearLayoutManager(this)
        rv.itemAnimator = null
        rv.adapter = adapter
        findViewById<TextView>(R.id.tvVersion).text = "KcKTV sürüm ${Updater.currentVersion(this)}"
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { Updater.check(this, manual = true) }

        backupUi = BackupUi(this)
        findViewById<Button>(R.id.btnBackup).setOnClickListener { backupUi.backup() }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { backupUi.restore() }
        val btnAuto = findViewById<Button>(R.id.btnAutoPlay)
        fun label() {
            btnAuto.text = if (Accounts.autoPlay(this)) "▶ Açılışta son kanal: Açık" else "▶ Açılışta son kanal: Kapalı"
        }
        label()
        btnAuto.setOnClickListener {
            Accounts.setAutoPlay(this, !Accounts.autoPlay(this))
            label()
        }
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
            (if (a.id == active) "✓  " else "     ") + "${a.title}   ($kind)" +
                "\n      " + (infos[a.id] ?: "Abonelik bilgisi yükleniyor…")
        } + "＋  Yeni hesap ekle"
        adapter.selected = -1
        adapter.notifyDataSetChanged()
        loadInfos()
        findViewById<RecyclerView>(R.id.rvAccounts).post {
            findViewById<RecyclerView>(R.id.rvAccounts).findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private var infosLoading = false

    /** Her hesabın bitiş tarihini ve bağlantı sayısını sağlayıcıdan al */
    private fun loadInfos() {
        if (infosLoading || accounts.all { infos.containsKey(it.id) }) return
        infosLoading = true
        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
        lifecycleScope.launch {
            for (acc in accounts) {
                if (infos.containsKey(acc.id)) continue
                val info = Repo.accountInfo(acc, force = true)
                infos[acc.id] = when {
                    info == null -> "Abonelik bilgisi alınamadı"
                    else -> buildString {
                        val d = info.daysLeft
                        when {
                            info.expiresAt == null -> append("📅 Süresiz")
                            d != null && d < 0 -> append("⛔ Süresi doldu: ${fmt.format(java.util.Date(info.expiresAt))}")
                            else -> {
                                append(if (d != null && d <= 7) "⚠ " else "📅 ")
                                append("Bitiş: ${fmt.format(java.util.Date(info.expiresAt))}  ($d gün kaldı)")
                            }
                        }
                        info.maxConnections?.let { append("   •   🔗 $it bağlantı") }
                        info.activeConnections?.let { if (info.maxConnections != null) append(" (şu an $it kullanımda)") }
                        if (info.status.isNotBlank() && !info.status.equals("Active", true)) append("   •   Durum: ${info.status}")
                    }
                }
                val keepFocus = currentFocus
                val old = adapter.items
                val activeId = Accounts.activeId(this@AccountsActivity)
                adapter.items = accounts.map { a ->
                    val kind = if (a.isXtream) "Xtream • ${a.username}" else "M3U"
                    (if (a.id == activeId) "✓  " else "     ") + "${a.title}   ($kind)" +
                        "\n      " + (infos[a.id] ?: "Abonelik bilgisi yükleniyor…")
                } + "＋  Yeni hesap ekle"
                if (old.size == adapter.items.size) adapter.notifyItemRangeChanged(0, accounts.size)
                else adapter.notifyDataSetChanged()
                keepFocus?.requestFocus()
            }
            infosLoading = false
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
