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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
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

    private val playerNames = listOf("Merah", "Biru")

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

        // Auto-host: buat game sebagai Player 1 di background.
        thread {
            try {
                val c = GameApi.createGame("Android")
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
        boardView.playPlayerMove(
            player = player,
            from = from,
            rideFrom = moveTo,
            rideTo = rideTo ?: moveTo,
            hasRide = hasRide,
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
