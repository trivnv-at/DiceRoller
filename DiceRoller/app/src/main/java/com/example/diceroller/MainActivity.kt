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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var rollButton: Button
    private lateinit var lbButton: Button

    private val playerNames = listOf("Merah", "Biru")

    // ---- Lapis-1: akun & cache leaderboard (SharedPreferences) ----
    private val prefs by lazy { getSharedPreferences("ular_tangga", Context.MODE_PRIVATE) }
    private var accountId: String? = null
    private var accountName: String? = null

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

    // Siklus random wajah dadu saat dadu berputar.
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

        statusTextView = findViewById(R.id.statusTextView)
        boardView = findViewById(R.id.boardView)
        diceImage = findViewById(R.id.diceImage)
        rollButton = findViewById(R.id.button)
        lbButton = findViewById(R.id.leaderboardButton)

        boardView.positions = positions
        boardView.currentPlayer = currentPlayer

        rollButton.isEnabled = false
        statusTextView.text = "Menghubungkan ke server…"

        rollButton.setOnClickListener {
            if (gameOver) {
                resetGame()
            } else {
                handleRoll()
            }
        }
        lbButton.setOnClickListener { showLeaderboard() }

        // Lapis-1: muat akun dari SharedPreferences.
        accountId = prefs.getString("account_id", null)
        accountName = prefs.getString("name", null)

        if (accountId != null) {
            // Akun sudah ada -> langsung buat game.
            startGame()
        } else {
            // Belum punya akun -> minta nama dulu (lapis-1).
            showNamePrompt()
        }
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
                            startGame()
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

    /** Tampilkan leaderboard dari server; fallback ke cache lokal (lapis-1). */
    private fun showLeaderboard() {
        statusTextView.text = getString(R.string.lb_loading)
        thread {
            try {
                val list = GameApi.fetchLeaderboard("elo", 10)
                // Simpan cache lokal.
                val cache = buildString {
                    append("rank\tname\tpoints\telo\twins\tdate\n")
                    list.forEach { append("${it.rank}\t${it.name}\t${it.points}\t${it.elo}\t${it.wins}\t${it.lastDate ?: "—"}\n") }
                }
                prefs.edit().putString("lb_cache", cache).apply()
                runOnUiThread { showLeaderboardDialog(list, false) }
            } catch (_: Exception) {
                val cached = prefs.getString("lb_cache", null)
                runOnUiThread {
                    if (cached != null) {
                        statusTextView.text = getString(R.string.lb_offline)
                        showLeaderboardDialog(parseCache(cached), true)
                    } else {
                        statusTextView.text = getString(R.string.lb_offline)
                    }
                }
            }
        }
    }

    private fun showLeaderboardDialog(list: List<GameApi.LeaderboardEntry>, offline: Boolean) {
        val sb = StringBuilder()
        sb.append("  #\tNama\t\tPoin\tELO\tMenang\tTanggal\n")
        sb.append("  ${"─".repeat(44)}\n")
        list.forEach {
            sb.append("  ${it.rank}.\t${it.name}\t${it.points}\t${it.elo}\t${it.wins}\t${it.lastDate ?: "—"}\n")
        }
        if (list.isEmpty()) sb.append("  ${getString(R.string.lb_empty)}\n")
        val title = if (offline) "🏆 Leaderboard (offline)" else getString(R.string.lb_title)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun parseCache(cache: String): List<GameApi.LeaderboardEntry> {
        val out = ArrayList<GameApi.LeaderboardEntry>()
        val lines = cache.lines().drop(1) // buang header
        lines.forEach { line ->
            val parts = line.trim().split("\t")
            if (parts.size >= 6) {
                val rank = parts[0].trimEnd('.').trim().toIntOrNull() ?: 0
                out.add(GameApi.LeaderboardEntry(
                    id = "", name = parts[1], points = parts[2].toIntOrNull() ?: 0,
                    elo = parts[3].toIntOrNull() ?: 0, wins = parts[4].toIntOrNull() ?: 0,
                    lastDate = parts[5].ifBlank { null }, rank = rank,
                ))
            }
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Mulai loop polling state server. */
    private fun startPolling() {
        polling = true
        handler.post(pollTick)
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
                    applyServerState(view)
                    animateRemoteMove(view)
                }
            } catch (_: Exception) {
                // Polling boleh gagal sesaat (server restart dsb); coba lagi nanti.
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
        boardView.positions = positions
        boardView.currentPlayer = currentPlayer

        if (view.status == "finished") {
            gameOver = true
            val winner = view.winner ?: currentPlayer
            statusTextView.text = "Pemain ${winner + 1} (${playerNames[winner]}) MENANG!"
            rollButton.text = getString(R.string.play_again)
            rollButton.isEnabled = true
            refreshOwnStats()
        } else {
            gameOver = false
            if (view.status == "waiting") {
                statusTextView.text = "Kode Game: ${creds?.gameCode}\nTunggu Player 2 join di web…"
                rollButton.isEnabled = false
            } else {
                updateTurnStatus(view)
            }
        }
    }

    /** Teks giliran + enabled state tombol roll. */
    private fun updateTurnStatus(view: GameApi.GameView) {
        val mine = creds?.seat == view.currentPlayer
        statusTextView.text = "Giliran Pemain ${view.currentPlayer + 1} (${playerNames[view.currentPlayer]})." +
            if (mine) " Lempar dadu!" else " Tunggu Player ${view.currentPlayer + 1}…"
        // Roll hanya aktif saat giliran kita (Player 1 host) & tidak ada animasi.
        rollButton.isEnabled = mine && !tokenAnimating
    }

    /**
     * Deteksi & animasikan gerakan Player 2 (seat 1) dari polling,
     * berdasarkan lastRoll baru yang belum pernah dianimasikan.
     */
    private fun animateRemoteMove(view: GameApi.GameView) {
        val lr = view.lastRoll ?: return
        val mySeat = creds?.seat ?: return
        if (lr.seat == mySeat) return // gerakan kita ditangani handleRoll()
        val key = "${lr.seat}:${lr.from}:${lr.to}"
        if (key == lastAnimatedRoll) return
        lastAnimatedRoll = key

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
            applyServerState(view)
            return
        }
        showDice(lr.value) {
            handler.postDelayed({
                if (lr.to != lr.from) {
                    animateToken(
                        player = lr.seat,
                        from = lr.from,
                        to = lr.to,
                        rideFrom = lr.rideFrom,
                        rideTo = lr.rideTo,
                    ) { applyServerState(view) }
                } else {
                    // Overshoot / tanpa gerakan — render langsung.
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
        rollButton.isEnabled = false
        thread {
            try {
                val view = GameApi.roll(c)
                runOnUiThread {
                    val lr = view.lastRoll
                    lastAnimatedRoll = if (lr != null) "${lr.seat}:${lr.from}:${lr.to}" else null
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
        rollButton.isEnabled = false
        thread {
            try {
                val view = GameApi.reset(c)
                runOnUiThread {
                    lastAnimatedRoll = null
                    gameOver = false
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
     * Animate the dice "rolling": spins a full turn while cycling random faces,
     * then settles on [roll] (nilai dari server). [onDone] dipanggil saat dadu
     * berhenti (setelah wajah akhir tampil).
     */
    private fun showDice(roll: Int, onDone: () -> Unit = {}) {
        diceImage.removeCallbacks(faceTick)
        diceImage.animate().cancel()
        diceAnimationRunning = true

        diceImage.postDelayed(faceTick, 70L)
        diceImage.animate()
            .rotationBy(360f)
            .setDuration(600L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                diceImage.removeCallbacks(faceTick)
                diceImage.rotation = 0f
                diceImage.setImageResource(drawableFor(roll))
                diceAnimationRunning = false
                onDone()
            }
            .start()
    }

    /** Map a dice roll (1..6) to its drawable resource. */
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
