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
        groupXtream = findViewById(R.id.groupXtream)
        groupM3u = findViewById(R.id.groupM3u)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        prefs.account?.let {
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

        val account = Account(
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
                prefs.account = account
                Repo.account = null
                Repo.sections.clear()
                startActivity(
                    Intent(this@LoginActivity, ChannelsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: Exception) {
                tvStatus.text = "Bağlanılamadı: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                btnSave.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }
}
