package com.iptv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var mode = Account.TYPE_XTREAM
    private var isNew = false
    private var editId: String? = null

    private lateinit var btnXtream: Button
    private lateinit var btnM3u: Button
    private lateinit var btnSave: Button
    private lateinit var etName: EditText
    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etM3u: EditText
    private lateinit var groupXtream: LinearLayout
    private lateinit var groupM3u: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = Prefs(this)

        btnXtream = findViewById(R.id.btnXtream)
        btnM3u = findViewById(R.id.btnM3u)
        btnSave = findViewById(R.id.btnSave)
        etName = findViewById(R.id.etName)
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etM3u = findViewById(R.id.etM3u)
        listOf(etName, etServer, etUser, etPass, etM3u).forEach { it.acceptNumpad() }
        groupXtream = findViewById(R.id.groupXtream)
        groupM3u = findViewById(R.id.groupM3u)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        // "new": yeni hesap ekle; "accountId": o hesabı düzenle; ikisi de yoksa açık hesap
        isNew = intent.getBooleanExtra("new", false) || Accounts.all(this).isEmpty()
        editId = if (isNew) null else (intent.getStringExtra("accountId") ?: Accounts.activeId(this))
        findViewById<TextView>(R.id.tvTitle).text = if (isNew && Accounts.all(this).isNotEmpty()) "Yeni hesap ekle" else "KcKTV"
        (if (isNew) null else Accounts.get(this, editId))?.let {
            mode = it.type
            etName.setText(it.name)
            etServer.setText(it.server)
            etUser.setText(it.username)
            etPass.setText(it.password)
            etM3u.setText(it.m3uUrl)
        }
        setMode(mode)

        btnXtream.setOnClickListener { setMode(Account.TYPE_XTREAM) }
        btnM3u.setOnClickListener { setMode(Account.TYPE_M3U) }
        btnSave.setOnClickListener { save() }

        val backupUi = BackupUi(this)
        findViewById<Button>(R.id.btnRestoreBackup).apply {
            visibility = if (Accounts.all(this@LoginActivity).isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { backupUi.restore() }
        }
        findViewById<TextView>(R.id.tvVersion).text = "KcKTV sürüm ${Updater.currentVersion(this)}"
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { Updater.check(this, manual = true) }

        // Şifre gizli başlar; düğmeyle göster / gizle
        val btnShowPass = findViewById<Button>(R.id.btnShowPass)
        var passVisible = false
        btnShowPass.setOnClickListener {
            passVisible = !passVisible
            val sel = etPass.selectionEnd
            etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (passVisible) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            etPass.setSelection(sel.coerceIn(0, etPass.text.length))
            btnShowPass.text = if (passVisible) "🙈 Gizle" else "👁 Göster"
        }

        if (mode == Account.TYPE_XTREAM) btnXtream.requestFocus() else btnM3u.requestFocus()
    }

    private fun setMode(m: String) {
        mode = m
        val x = m == Account.TYPE_XTREAM
        groupXtream.visibility = if (x) View.VISIBLE else View.GONE
        groupM3u.visibility = if (x) View.GONE else View.VISIBLE
        btnXtream.isActivated = x
        btnM3u.isActivated = !x
        tvStatus.text = ""
    }

    private fun save() {
        val x = mode == Account.TYPE_XTREAM
        val server = etServer.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString().trim()
        val m3u = etM3u.text.toString().trim()

        if (x && (server.isEmpty() || user.isEmpty() || pass.isEmpty())) {
            tvStatus.text = "Sunucu adresi, kullanıcı adı ve şifre gerekli."
            return
        }
        if (!x && m3u.isEmpty()) {
            tvStatus.text = "M3U linkini gir."
            return
        }

        val id = editId ?: Accounts.newId(this)
        val account = Account(
            id = id,
            type = mode,
            name = etName.text.toString().trim(),
            server = if (x) Repo.normalizeServer(server) else "",
            username = if (x) user else "",
            password = if (x) pass else "",
            m3uUrl = if (x) "" else m3u,
        )

        btnSave.isEnabled = false
        progress.visibility = View.VISIBLE
        tvStatus.text = ""

        lifecycleScope.launch {
            try {
                if (x) Repo.checkXtream(account) else Repo.checkM3u(account)
                val old = editId?.let { Accounts.get(this@LoginActivity, it) }
                val others = Accounts.all(this@LoginActivity).filter { it.id != id }
                Accounts.save(this@LoginActivity, account)
                Accounts.setActive(this@LoginActivity, id)
                // Aynı hesap düzenlendi (ör. yenilenen abonelik): ayarlar kalır, kayıtlı adresler güncellenir
                if (old != null) Transfer.accountChanged(this@LoginActivity, id, old, account)
                if (old == null && others.isNotEmpty()) askCopy(account, others) else goChannels()
            } catch (e: Exception) {
                tvStatus.text = "Bağlanılamadı: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                btnSave.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun goChannels() {
        Repo.account = null
        Repo.sections.clear()
        Repo.pipChannel = null
        Repo.liveList = emptyList()
        Repo.liveIndex = -1
        startActivity(
            Intent(this, ChannelsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Yeni hesap: önceki bir hesabın ayarları aktarılsın mı? */
    private fun askCopy(account: Account, others: List<Account>) {
        val sorted = others.sortedByDescending { Transfer.sameServer(it, account) }
        val names = sorted.map { o ->
            "↪  ${o.title}" + if (Transfer.sameServer(o, account)) "   (aynı sağlayıcı ✓)" else "   (farklı sağlayıcı)"
        } + "✕  Hayır, boş başla"
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Önceki ayarlar aktarılsın mı?")
            .setItems(names.toTypedArray()) { _, w ->
                val src = sorted.getOrNull(w)
                if (src == null) {
                    goChannels()
                    return@setItems
                }
                val go = {
                    Transfer.copy(this, src, account)
                    android.widget.Toast.makeText(this, "Ayarlar aktarıldı: ${src.title} → ${account.title}", android.widget.Toast.LENGTH_LONG).show()
                    goChannels()
                }
                if (Transfer.sameServer(src, account)) {
                    go()
                } else {
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Farklı sağlayıcı")
                        .setMessage("Bu iki hesap farklı sağlayıcılardan. Kategori ve kanal numaraları farklı olduğu için favoriler, sıralamalar ve gizlemeler büyük ihtimalle tutmaz.\n\nYine de aktarılsın mı?")
                        .setPositiveButton("Aktar") { _, _ -> go() }
                        .setNegativeButton("Boş başla") { _, _ -> goChannels() }
                        .show()
                }
            }
            .setCancelable(false)
            .show()
    }
}
