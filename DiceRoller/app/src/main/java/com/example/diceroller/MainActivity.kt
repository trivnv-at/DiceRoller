/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.diceroller

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/**
 * Ular Tangga multiplayer — Android sebagai **Player 1 (host)**.
 *
 * Game kini **server-authoritative**: dadu, aturan (pas-100, extra-turn,
 * ular/tangga) & giliran ditentukan backend (GameApi). Android hanya:
 *   - auto-buat game saat dibuka & menampilkan kode untuk Player 2 join di web,
 *   - mengirim roll & merender state dari server,
 *   - polling state tiap ~1.5s untuk sinkron gerakan Player 2.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var boardView: UlarTanggaView
    private lateinit var diceImage: ImageView
    private lateinit var menuButton: MaterialButton
    // Boleh beralih ke "Main vs Bot" hanya saat game berstatus waiting (Player 2 belum join).
    private var botAvailable = false

    private val playerNames = listOf("Merah", "Biru")

    // ---- Lapis-1: akun & cache leaderboard (SharedPreferences) ----
    private val prefs by lazy { getSharedPreferences("ular_tangga", Context.MODE_PRIVATE) }
    private var accountId: String? = null
    private var accountName: String? = null

    // State leaderboard (dialog custom, tab ELO/Poin).
    private var lbSort: String = "elo"
    private var currentLbDialog: AlertDialog? = null

    // Kredensial & identitas pemain lokal (Player 1 / host).
    private var creds: GameApi.PlayerCreds? = null

    // State yang dirender (cerminan server). server-authoritative.
    private var positions = intArrayOf(0, 0)
    private var currentPlayer = 0
    private var gameOver = false

    // True selama animasi dadu berjalan (cegah tap ganda).
    private var diceAnimationRunning = false
    // True selama token bergerak (cegah tap).
    private var tokenAnimating = false
    // True selama ada HTTP in-flight, cegah overlap poll/roll.
    private var networkingActive = false
    // Saat ada roll belum dianimasikan: tahan token pemain itu di posisi asal
    // (from) selama jeda dadu+delay, agar tidak "teleport" ke posisi akhir
    // (mirror web yg menahan token di posisi lama). -1 = tidak ada roll pending.
    private var pendingSeat = -1
    private var pendingFrom = 0

    // Polling tiap 0.5s untuk sinkron gerakan Player 2.
    private val pollDelayMs = 500L
    private var polling = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollTick = object : Runnable {
        override fun run() {
            pollOnce()
            if (polling) handler.postDelayed(this, pollDelayMs)
        }
    }

    // Snapshot roll yang sudah dianimasikan (untuk deteksi gerakan Player 2).
    private var lastAnimatedRoll: String? = null
    // Kunci transisi state terakhir — hanya log saat state berubah (bukan tiap poll).
    private var lastStateKey: String? = null

    // Siklus random wajah dadu saat dadu berputar (gambar dadu biru).
    private val faceTick = object : Runnable {
        override fun run() {
            if (!diceAnimationRunning) return
            diceImage.setImageResource(drawableFor((1..6).random()))
            diceImage.postDelayed(this, 70L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SoundPlayer.init(this) // siapkan efek suara + haptic

        statusTextView = findViewById(R.id.statusTextView)
        boardView = findViewById(R.id.boardView)
        diceImage = findViewById(R.id.diceImage)
        menuButton = findViewById(R.id.menuButton)

        boardView.positions = positions
        boardView.currentPlayer = currentPlayer

        setDiceEnabled(false) // menunggu / belum giliran -> dadu redup & tak bisa tap
        botAvailable = false // aktif saat game "waiting" (Player 2 belum join)
        statusTextView.text = "Menghubungkan ke server…"

        // Lempar dadu dengan mengetuk dadu (bukan tombol). Saat game selesai,
        // ketuk dadu untuk main lagi (rematch).
        diceImage.setOnClickListener {
            if (gameOver) {
                resetGame()
            } else {
                handleRoll()
            }
        }
        menuButton.setOnClickListener { showMenu() }

        // Lapis-1: muat akun dari SharedPreferences.
        accountId = prefs.getString("account_id", null)
        accountName = prefs.getString("name", null)

        if (accountId != null) {
            // Akun sudah ada -> tanyakan mode (Host vs Web / Main vs Bot).
            showModePicker()
        } else {
            // Belum punya akun -> minta nama dulu (lapis-1).
            showNamePrompt()
        }
    }

    /**
     * Dialog pilihan mode saat app dibuka: Host vs Web (bagikan kode) atau
     * Main vs Bot. Menghindari auto-host diam-diam sehingga pemain memilih
     * mode secara eksplisit (dan tidak membuat game PvP waiting saat hanya
     * ingin main vs Bot).
     */
    private fun showModePicker() {
        val options = arrayOf(getString(R.string.mode_host_web), getString(R.string.mode_vs_bot))
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_pick_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startGame()
                    1 -> startBotGame()
                }
            }
            .setCancelable(false)
            .show()
    }

    /** Simpan akun (lapis-1) lalu buat game sebagai Player 1. */
    private fun startGame() {
        statusTextView.text = "Menghubungkan ke server…"
        val name = accountName ?: "Player"
        val acc = accountId
        thread {
            try {
                val c = if (acc != null) GameApi.createGame(name, acc) else GameApi.createGame(name)
                runOnUiThread {
                    creds = c
                    statusTextView.text = "Kode Game: ${c.gameCode}\nTunggu Player 2 join di web…"
                    startPolling()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusTextView.text = "Gagal terhubung: ${e.message}\nCek server / internet."
                }
            }
        }
    }

    /**
     * Main vs Bot: tinggalkan game PvP yang masih menunggu Player 2,
     * buat game baru dgn `vsBot:true` (server auto-join bot sebagai seat 1),
     * lalu mulai polling — bot bergerak otomatis (lazy, server-side).
     */
    private fun startBotGame() {
        if (networkingActive) return
        stopPolling()                      // lepas polling game PvP lama
        lastAnimatedRoll = null
        pendingSeat = -1
        gameOver = false
        botAvailable = false
        statusTextView.text = "Menyiapkan lawan Bot…"
        networkingActive = true
        val name = accountName ?: "Player"
        val acc = accountId
        thread {
            try {
                val c = GameApi.createGame(name, acc, vsBot = true)
                runOnUiThread {
                    creds = c
                    statusTextView.text = "Main vs Bot — kode ${c.gameCode}"
                    startPolling()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusTextView.text = "Gagal buat game bot: ${e.message}\nCek server / internet."
                }
            } finally {
                networkingActive = false
            }
        }
    }

    /** Dialog minta nama (get-or-create akun), lalu lanjut buat game. */
    private fun showNamePrompt() {
        val input = EditText(this)
        input.hint = getString(R.string.prompt_name_hint)
        val pad = (resources.displayMetrics.density * 16).toInt()
        input.setPadding(pad, pad, pad, pad)
        val wrap = LinearLayout(this)
        wrap.setPadding(pad * 2, pad, pad * 2, 0)
        wrap.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.prompt_name_title)
            .setView(wrap)
            .setCancelable(false)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Player" }
                statusTextView.text = "Membuat akun…"
                thread {
                    try {
                        val accId = GameApi.createPlayer(name)
                        runOnUiThread {
                            accountId = accId
                            accountName = name
                            prefs.edit().putString("account_id", accId).putString("name", name).apply()
                            showModePicker()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            statusTextView.text = "Gagal membuat akun: ${e.message}\nCek server / internet."
                        }
                    }
                }
            }
            .show()
    }

    /** Buka menu dropdown kanan atas (☰): Leaderboard & Main vs Bot. */
    private fun showMenu() {
        val popup = PopupMenu(this, menuButton)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        popup.menu.findItem(R.id.action_bot).isEnabled = botAvailable
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_leaderboard -> {
                    showLeaderboard()
                    true
                }
                R.id.action_bot -> {
                    startBotGame()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Tampilkan leaderboard dari server; fallback ke cache lokal (lapis-1). */
    private fun showLeaderboard() {
        statusTextView.text = getString(R.string.lb_loading)
        fetchAndShow(lbSort)
    }

    private fun fetchAndShow(sort: String) {
        thread {
            try {
                val list = GameApi.fetchLeaderboard(sort, 10)
                // Simpan cache lokal (8 kolom: id, rank, name, points, elo, wins, games, date).
                val cache = buildString {
                    append("id\trank\tname\tpoints\telo\twins\tgames\tdate\n")
                    list.forEach { append("${it.id}\t${it.rank}\t${it.name}\t${it.points}\t${it.elo}\t${it.wins}\t${it.games}\t${it.lastDate ?: "—"}\n") }
                }
                prefs.edit().putString("lb_cache", cache).apply()
                runOnUiThread { showLeaderboardDialog(list, false, sort) }
            } catch (_: Exception) {
                val cached = prefs.getString("lb_cache", null)
                runOnUiThread {
                    if (cached != null) {
                        statusTextView.text = getString(R.string.lb_offline)
                        showLeaderboardDialog(parseCache(cached), true, sort)
                    } else {
                        statusTextView.text = getString(R.string.lb_offline)
                    }
                }
            }
        }
    }

    /** Pindah tab ELO/Poin: tutup dialog lama lalu fetch & tampilkan lagi. */
    private fun switchLbSort(sort: String) {
        currentLbDialog?.dismiss()
        lbSort = sort
        fetchAndShow(sort)
    }

    /** Dialog leaderboard bergaya ref-demo/leaderboard.html: podium + list + win-rate. */
    private fun showLeaderboardDialog(list: List<GameApi.LeaderboardEntry>, offline: Boolean, sort: String) {
        currentLbDialog?.dismiss()
        lbSort = sort
        val c = palette()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // Header
        root.addView(TextView(this).apply {
            text = getString(R.string.lb_title)
            setTextColor(c.ink)
            setTypeface(null, Typeface.BOLD)
            textSize = 20f
        })
        if (offline) root.addView(TextView(this).apply {
            text = getString(R.string.lb_offline)
            setTextColor(c.red)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        })

        // Tab ELO / Poin
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        tabs.addView(tabButton("ELO", sort == "elo", c) { switchLbSort("elo") })
        tabs.addView(tabButton("Poin", sort == "points", c) { switchLbSort("points") })
        root.addView(tabs)

        if (list.isEmpty()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.lb_empty)
                setTextColor(c.inkSoft)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            })
        } else {
            root.addView(podiumRow(list, c))
            root.addView(listBlock(list, c))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(root); isVerticalScrollBarEnabled = true })
            .setPositiveButton(R.string.action_ok, null)
            .create()
        currentLbDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** Baris tab ELO/Poin ala referensi (pil putih utk tab aktif). */
    private fun tabButton(label: String, active: Boolean, c: LbPalette, onClick: () -> Unit): View {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (active) c.ink else c.inkSoft)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (active) 0xFFFFFFFF.toInt() else Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    /** Podium top-3, urutan runner-up / juara / ketiga (g2 g1 g3) seperti referensi. */
    private fun podiumRow(list: List<GameApi.LeaderboardEntry>, c: LbPalette): View {
        val podium = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        val top = list.take(3)
        val order = listOf(1, 0, 2).mapNotNull { top.getOrNull(it) }
        order.forEachIndexed { oi, p ->
            // podium idx: 0->g2(perak), 1->g1(emas), 2->g3(perunggu)
            podium.addView(podCard(p, oi, c))
        }
        return podium
    }

    private fun podCard(p: GameApi.LeaderboardEntry, oi: Int, c: LbPalette): View {
        val medal = arrayOf("🥈", "🥇", "🥉")[oi]
        val barH = intArrayOf(62, 84, 44)[oi]
        val topC = intArrayOf(0xFFDFE4EC.toInt(), 0xFFFFD166.toInt(), 0xFFF0B087.toInt())[oi]
        val botC = intArrayOf(0xFFAEB9C9.toInt(), 0xFFF2A63C.toInt(), 0xFFC97B4F.toInt())[oi]
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(TextView(this).apply {
            text = medal
            textSize = 24f
            gravity = Gravity.CENTER
        })
        card.addView(avatar(p, 29, 20f, true))
        card.addView(TextView(this).apply {
            text = p.name
            setTextColor(c.ink)
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            maxLines = 1
        })
        card.addView(TextView(this).apply {
            text = if (lbSort == "points") "${p.points}" else "${p.elo}"
            setTextColor(c.inkSoft)
            setTypeface(null, Typeface.BOLD)
            textSize = 12f
        })
        card.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                colors = intArrayOf(topC, botC)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(barH))
        })
        return card
    }

    /** List peringkat (rank 4+); tiap baris: avatar, nama+date+winbar, win-rate, strip statistik. */
    private fun listBlock(list: List<GameApi.LeaderboardEntry>, c: LbPalette): View {
        val listView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        list.drop(3).forEach { listView.addView(rowView(it, c)) }
        return listView
    }

    private fun rowView(p: GameApi.LeaderboardEntry, c: LbPalette): View {
        val me = p.id.isNotEmpty() && p.id == accountId
        val wr = if (p.games > 0) Math.round(p.wins * 100f / p.games) else 0

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            val d = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(c.row) }
            if (me) d.setStroke(dp(2), c.p2)
            background = d
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            layoutParams = lp
        }

        // Baris atas: rank, avatar, nama+date+winbar, win-rate
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "#${p.rank}"
            setTextColor(c.inkSoft)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        top.addView(avatar(p, 34, 13f, false))
        val nm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(8), 0)
        }
        nm.addView(TextView(this).apply {
            text = p.name
            setTextColor(c.ink)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            maxLines = 1
        })
        nm.addView(TextView(this).apply {
            text = fmtLbDate(p.lastDate)
            setTextColor(c.inkSoft)
            textSize = 10f
            maxLines = 1
        })
        nm.addView(winBar(wr, c))
        top.addView(nm)
        val wrV = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        wrV.addView(TextView(this).apply {
            text = "$wr%"
            setTextColor(c.green)
            setTypeface(null, Typeface.BOLD)
            textSize = 15f
        })
        wrV.addView(TextView(this).apply {
            text = "WIN-RATE"
            setTextColor(c.inkSoft)
            textSize = 8f
        })
        top.addView(wrV)
        row.addView(top)

        // Strip statistik Poin / ELO / Menang / Game (highlight kolom sort aktif).
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        fun stat(v: Int, k: String, hl: Boolean) = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "$v"
                setTextColor(if (hl) c.gold else c.ink)
                setTypeface(null, Typeface.BOLD)
                textSize = 13f
            })
            addView(TextView(this@MainActivity).apply {
                text = k.uppercase()
                setTextColor(c.inkSoft)
                textSize = 8f
            })
        }
        stats.addView(stat(p.points, "Poin", lbSort == "points"))
        stats.addView(stat(p.elo, "ELO", lbSort == "elo"))
        stats.addView(stat(p.wins, "Menang", false))
        stats.addView(stat(p.games, "Game", false))
        row.addView(stats)
        return row
    }

    /** Avatar lingkaran berisi inisial pemain. */
    private fun avatar(p: GameApi.LeaderboardEntry, sizeDp: Int, fontSize: Float, stroke: Boolean): View {
        return TextView(this).apply {
            text = initialsOf(p.name)
            textSize = fontSize
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            val d = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColor(p.rank))
                if (stroke) setStroke(dp(2), Color.WHITE)
            }
            background = d
            val lp = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            lp.setMargins(0, 0, 0, 0)
            layoutParams = lp
        }
    }

    private fun avatarColor(rank: Int): Int = AVATAR_COLORS[(rank - 1).mod(AVATAR_COLORS.size)]

    private fun winBar(wr: Int, c: LbPalette): View {
        val fill = Math.min(wr, 100).coerceAtLeast(0)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(c.winbarBg)
            }
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(c.green) }
                layoutParams = LinearLayout.LayoutParams(0, dp(6), fill.toFloat())
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(6), (100 - fill).toFloat())
            })
        }
    }

    private fun fmtLbDate(d: String?): String {
        if (d.isNullOrBlank() || d == "—") return "—"
        val parts = d.split("-")
        if (parts.size != 3) return d
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
        val m = parts[1].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: return d
        return "${parts[2]} $m ${parts[0]}"
    }

    private fun initialsOf(n: String): String {
        val s = n.trim().split("\\s+".toRegex()).map { it.firstOrNull()?.toString() ?: "" }.joinToString("")
        return s.ifEmpty { "?" }.take(2).uppercase()
    }

    private fun parseCache(cache: String): List<GameApi.LeaderboardEntry> {
        val out = ArrayList<GameApi.LeaderboardEntry>()
        val lines = cache.lines().drop(1) // buang header
        lines.forEach { line ->
            val parts = line.trim().split("\t")
            if (parts.size >= 8) {
                out.add(GameApi.LeaderboardEntry(
                    id = parts[0], rank = parts[1].trimEnd('.').trim().toIntOrNull() ?: 0,
                    name = parts[2], points = parts[3].toIntOrNull() ?: 0,
                    elo = parts[4].toIntOrNull() ?: 0, wins = parts[5].toIntOrNull() ?: 0,
                    games = parts[6].toIntOrNull() ?: 0,
                    lastDate = if (parts[7].isNotBlank() && parts[7] != "—") parts[7] else null,
                ))
            }
        }
        return out
    }

    /** Palet warm leaderboard (gaya ref-demo/leaderboard.html), adaptif light/dark. */
    private fun palette(): LbPalette {
        val n = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return LbPalette(
            panel = if (n) 0xFF1E2430.toInt() else 0xFFFFF9EC.toInt(),
            ink = if (n) 0xFFE2E8F0.toInt() else 0xFF4A2C14.toInt(),
            inkSoft = if (n) 0xFF94A3B8.toInt() else 0xFF8A6B4A.toInt(),
            line = if (n) 0xFF334155.toInt() else 0xFFEED9AE.toInt(),
            row = if (n) 0xFF1A2230.toInt() else 0xFFFFFFFF.toInt(),
            winbarBg = if (n) 0xFF2A3346.toInt() else 0xFFF0DFB8.toInt(),
            gold = 0xFFF2A63C.toInt(),
            green = 0xFF3FAE5A.toInt(),
            p2 = 0xFF3D9BFF.toInt(),
            red = 0xFFE2553F.toInt(),
        )
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "onDestroy polling stopped")
    }

    /** Mulai loop polling state server. */
    private fun startPolling() {
        polling = true
        handler.post(pollTick)
    }

    /** Hentikan loop polling (pollTick berhenti menjadwalkan ulang). */
    private fun stopPolling() {
        polling = false
    }

    /** Ambil state terbaru & render; animasikan gerakan Player 2 bila ada. */
    private fun pollOnce() {
        val c = creds ?: return
        if (networkingActive) return
        networkingActive = true
        thread {
            try {
                val view = GameApi.getGame(c)
                runOnUiThread {
                    // Deteksi gerakan dulu (set pendingSeat) SEBELUM applyServerState,
                    // agar token ditahan di posisi asal (tanpa flash ke posisi akhir).
                    animateRemoteMove(view)
                    applyServerState(view)
                }
            } catch (e: Exception) {
                // Polling boleh gagal sesaat (server restart dsb); coba lagi nanti.
                Log.w(TAG, "poll failed: ${e.message}")
            } finally {
                networkingActive = false
            }
        }
    }

    /**
     * Render state server ke UI: posisi, giliran, status, winner.
     */
    private fun applyServerState(view: GameApi.GameView) {
        positions = view.positions
        currentPlayer = view.currentPlayer
        // Log transisi state (status/turn/posisi/winner) hanya saat berubah,
        // supaya polling 0,5s tidak membanjiri logcat.
        val key = "${view.status}|${view.currentPlayer}|${view.winner}|${view.positions[0]}-${view.positions[1]}"
        if (key != lastStateKey) {
            lastStateKey = key
            Log.d(TAG, "state $key")
        }
        // Selama ada roll yang belum dianimasikan, tahan token pemain itu di
        // posisi asal (from) — jangan biarkan polling langsung menaruhnya di
        // posisi akhir (yang memicu "teleport" di awal perpindahan).
        val renderPos = if (pendingSeat in positions.indices) {
            positions.copyOf().also { it[pendingSeat] = pendingFrom }
        } else {
            positions
        }
        boardView.positions = renderPos
        boardView.currentPlayer = currentPlayer

        if (view.status == "finished") {
            gameOver = true
            val winner = view.winner ?: currentPlayer
            Log.i(TAG, "game finished winner=$winner")
            statusTextView.text = "Pemain ${winner + 1} (${playerNames[winner]}) MENANG!\nKetuk dadu untuk main lagi"
            setDiceEnabled(true)
            SoundPlayer.win() // arpeggio menang + getar panjang
            SoundPlayer.vibrateWin()
            refreshOwnStats()
        } else {
            gameOver = false
            if (view.status == "waiting") {
                statusTextView.text = "Kode Game: ${creds?.gameCode}\nTunggu Player 2 join di web…"
                setDiceEnabled(false)
                // Belum ada Player 2 -> boleh beralih ke "Main vs Bot".
                botAvailable = true
            } else {
                updateTurnStatus(view)
                // Game sedang/sudah dimainkan -> jangan biarkan beralih ke bot.
                botAvailable = false
            }
        }
    }

    /** Aktifkan/nonaktifkan tap dadu + visual (redup saat nonaktif). */
    private fun setDiceEnabled(enabled: Boolean) {
        diceImage.isEnabled = enabled
        diceImage.alpha = if (enabled) 1f else 0.4f
    }

    /** Teks giliran + enabled state dadu (pengganti tombol roll). */
    private fun updateTurnStatus(view: GameApi.GameView) {
        val mine = creds?.seat == view.currentPlayer
        statusTextView.text = "Giliran Pemain ${view.currentPlayer + 1} (${playerNames[view.currentPlayer]})." +
            if (mine) " Ketuk dadu!" else " Tunggu Player ${view.currentPlayer + 1}…"
        // Dadu hanya bisa di-tap saat giliran kita (Player 1 host) & tidak ada animasi.
        setDiceEnabled(mine && !tokenAnimating)
    }

    /**
     * Deteksi & animasikan gerakan Player 2 (seat 1) dari polling,
     * berdasarkan lastRoll baru yang belum pernah dianimasikan.
     */
    private fun animateRemoteMove(view: GameApi.GameView) {
        val lr = view.lastRoll ?: return
        val mySeat = creds?.seat ?: return
        if (lr.seat == mySeat) return // gerakan kita ditangani handleRoll()
        // Sertakan `value`: roll yang TIDAK menggerakkan token (from==to, mis. overshoot >100)
        // bisa berbagi key seat:from:to padahal nilai dadu beda — tanpanya dadu tak di-update
        // ke nilai baru (stale), jadi gambar dadu tak cocok dgn nilai yang diperoleh.
        val key = "${lr.seat}:${lr.from}:${lr.to}:${lr.value}"
        if (key == lastAnimatedRoll) return
        lastAnimatedRoll = key
        Log.d(TAG, "remote move seat=${lr.seat} ${lr.from}->${lr.to}")

        // Dadu dulu, lalu token bergerak setelah dadu berhenti + 0,5s.
        applyDelayedMove(view, lr)
    }

    /**
     * Render hasil roll: tampilkan dadu (600ms), lalu gerakkan token SETELAH
     * dadu berhenti + 500ms — sinkron dgn web. Dipakai untuk roll sendiri &
     * gerakan pemain remote.
     */
    private fun applyDelayedMove(view: GameApi.GameView, lr: GameApi.LastRoll?) {
        if (lr == null) {
            pendingSeat = -1
            applyServerState(view)
            return
        }
        // Tandai roll pending: tahan token pemain ini di posisi asal (from)
        // selama dadu berputar + delay, hingga animasi selesai.
        pendingSeat = lr.seat
        pendingFrom = lr.from
        showDice(lr.value) {
            handler.postDelayed({
                if (lr.to != lr.from) {
                    animateToken(
                        player = lr.seat,
                        from = lr.from,
                        to = lr.to,
                        rideFrom = lr.rideFrom,
                        rideTo = lr.rideTo,
                    ) {
                        pendingSeat = -1
                        applyServerState(view)
                    }
                } else {
                    // Overshoot / tanpa gerakan — render langsung.
                    pendingSeat = -1
                    applyServerState(view)
                }
            }, 800L)
        }
    }

    /** Kirim roll ke server, lalu animasikan hasilnya. */
    private fun handleRoll() {
        val c = creds ?: return
        if (networkingActive || tokenAnimating || gameOver) return
        networkingActive = true
        // Dadu tetap terang saat berputar; re-entry dicegah guard networkingActive.
        thread {
            try {
                val view = GameApi.roll(c)
                runOnUiThread {
                    val lr = view.lastRoll
                    if (lr != null) Log.d(TAG, "roll seat=${lr.seat} value=${lr.value} ${lr.from}->${lr.to}")
                    lastAnimatedRoll = if (lr != null) "${lr.seat}:${lr.from}:${lr.to}:${lr.value}" else null
                    applyDelayedMove(view, lr)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusTextView.text = "Gagal roll: ${e.message}"
                    updateTurnStatus(GameApi.GameView(
                        status = "playing", positions = positions,
                        currentPlayer = currentPlayer, winner = null, lastRoll = null,
                    ))
                }
            } finally {
                networkingActive = false
            }
        }
    }

    /**
     * Animasikan token dari [from] ke [to] (naik tangga / turun ular bila ada
     * ride), lalu panggil [onDone]. Dipakai untuk gerakan sendiri & remote.
     */
    private fun animateToken(
        player: Int,
        from: Int,
        to: Int,
        rideFrom: Int?,
        rideTo: Int?,
        onDone: () -> Unit,
    ) {
        tokenAnimating = true
        // Ride terdeteksi dari rideFrom/rideTo (BUKAN `to`): `to` sudah = posisi
        // akhir/resolved (= rideTo saat ada ride), jadi `rideTo != to` selalu
        // false (Bug #12). Pakai rideFrom != rideTo.
        val hasRide = rideFrom != null && rideFrom != rideTo
        // MOVE berhenti di dasar tangga / kepala ular (rideFrom), atau di `to`
        // bila tanpa ride.
        val moveTo = rideFrom ?: to
        // Landing squash lebih dalam saat menang (mendarat di 100, pas-100).
        val isWin = (rideTo ?: to) >= 100
        boardView.playPlayerMove(
            player = player,
            from = from,
            rideFrom = moveTo,
            rideTo = rideTo ?: moveTo,
            hasRide = hasRide,
            isWin = isWin,
            onDone = {
                tokenAnimating = false
                onDone()
            }
        )
    }

    /** Reset / rematch via server. */
    private fun resetGame() {
        val c = creds ?: return
        if (networkingActive) return
        networkingActive = true
        thread {
            try {
                val view = GameApi.reset(c)
                runOnUiThread {
                    lastAnimatedRoll = null
                    gameOver = false
                    Log.d(TAG, "game reset")
                    applyServerState(view)
                }
            } catch (e: Exception) {
                runOnUiThread { statusTextView.text = "Gagal reset: ${e.message}" }
            } finally {
                networkingActive = false
            }
        }
    }

    /** Perbarui rekap akun sendiri (poin/ELO/menang) ke SharedPreferences (lapis-1). */
    private fun refreshOwnStats() {
        val acc = accountId ?: return
        thread {
            try {
                val rec = GameApi.getPlayer(acc)
                prefs.edit()
                    .putInt("stats_elo", rec.elo)
                    .putInt("stats_points", rec.points)
                    .putInt("stats_wins", rec.wins)
                    .putString("stats_date", rec.lastDate)
                    .apply()
            } catch (_: Exception) {
                // Rekap gagal -> biarkan cache lama.
            }
        }
    }

    /**
     * Animate the dice "rolling": berputar satu putaran sambil siklus gambar
     * dadu biru acak, lalu berhenti di [roll] (nilai dari server). [onDone]
     * dipanggil saat dadu berhenti (setelah wajah akhir tampil).
     */
    private fun showDice(roll: Int, onDone: () -> Unit = {}) {
        diceImage.removeCallbacks(faceTick)
        diceImage.animate().cancel()
        diceAnimationRunning = true

        SoundPlayer.roll() // suara dadu berputar + getar
        SoundPlayer.vibrateShort()
        diceImage.postDelayed(faceTick, 70L)
        diceImage.animate()
            .rotationBy(360f)
            .setDuration(600L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                diceImage.removeCallbacks(faceTick)
                diceImage.rotation = 0f
                diceImage.setImageResource(drawableFor(roll))
                SoundPlayer.land() // dadu mendarat di nilai akhir
                diceAnimationRunning = false
                onDone()
            }
            .start()
    }

    /** Map a dice roll (1..6) to its drawable resource (gambar dadu biru). */
    private fun drawableFor(roll: Int): Int {
        return when (roll) {
            1 -> R.drawable.dice_1
            2 -> R.drawable.dice_2
            3 -> R.drawable.dice_3
            4 -> R.drawable.dice_4
            5 -> R.drawable.dice_5
            else -> R.drawable.dice_6
        }
    }
}

/** Warna latar avatar leaderboard per peringkat (sama dgn referensi AVATAR). */
private val AVATAR_COLORS = intArrayOf(
    0xFFF2A63C.toInt(), 0xFFE2553F.toInt(), 0xFF3D9BFF.toInt(), 0xFF3FAE5A.toInt(),
    0xFF9B6BFF.toInt(), 0xFFFF6A8A.toInt(), 0xFF37B6A0.toInt(), 0xFFE67E22.toInt(),
)

/** Palet warm leaderboard (gaya ref-demo/leaderboard.html). */
private data class LbPalette(
    val panel: Int,
    val ink: Int,
    val inkSoft: Int,
    val line: Int,
    val row: Int,
    val winbarBg: Int,
    val gold: Int,
    val green: Int,
    val p2: Int,
    val red: Int,
)
