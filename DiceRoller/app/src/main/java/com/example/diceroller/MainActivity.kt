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
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * This activity hosts a two-player Snakes and Ladders game. Players take turns
 * rolling a dice, move along the board, and climb ladders or slide down snakes.
 * Rolling a 6 grants an extra turn. The first player to reach square 100 wins.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var boardView: UlarTanggaView
    private lateinit var diceImage: ImageView
    private lateinit var rollButton: Button

    private val playerNames = listOf("Merah", "Biru")
    private var positions = intArrayOf(0, 0)
    private var currentPlayer = 0
    private var gameOver = false

    // True while the dice roll animation is running, so rapid taps cannot start
    // overlapping roll animations.
    private var diceAnimationRunning = false

    // True while the player token is animating across the board, so taps are
    // ignored until the move, ladder/snake ride and bounce finish.
    private var tokenAnimating = false

    // Cycles random dice faces every ~70ms while the dice is spinning.
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

        rollButton.setOnClickListener { roll() }
    }

    /**
     * Roll the dice, move the current player, resolve any ladder or snake, and
     * hand the turn over (unless a 6 was rolled, which grants an extra turn).
     */
    private fun roll() {
        if (gameOver) {
            resetGame()
            return
        }
        if (tokenAnimating) return

        val roll = Dice(6).roll()
        showDice(roll)

        val player = currentPlayer
        val from = positions[player]
        val newPos = from + roll

        // The player must land exactly on square 100 to win.
        if (newPos > 100) {
            statusTextView.text = "Pemain ${player + 1} (${
                playerNames[player]
            }) lempar $roll — harus pas 100. Giliran Pemain ${2 - player}."
            currentPlayer = 1 - player
            boardView.currentPlayer = currentPlayer
            return
        }

        // Resolve any ladder/snake to know the final resting square before the
        // animation, so the token can ride up/down instead of teleporting.
        var finalPos = newPos
        var hasRide = false
        UlarTanggaView.LADDERS[newPos]?.let { top ->
            finalPos = top
            hasRide = true
        }
        UlarTanggaView.SNAKES[newPos]?.let { tail ->
            finalPos = tail
            hasRide = true
        }

        val baseMessage = "Pemain ${player + 1} (${playerNames[player]}) lempar $roll → $newPos"

        tokenAnimating = true
        boardView.playPlayerMove(
            player = player,
            from = from,
            to = newPos,
            rideFrom = newPos,
            rideTo = finalPos,
            hasRide = hasRide,
            onDone = {
                tokenAnimating = false
                finishRoll(player, finalPos, newPos, roll, hasRide, baseMessage)
            }
        )
    }

    /**
     * Apply the move result once the token animation has finished: update the
     * position, build the status message, and hand over the turn.
     */
    private fun finishRoll(
        player: Int,
        finalPos: Int,
        landedPos: Int,
        roll: Int,
        hasRide: Boolean,
        baseMessage: String
    ) {
        positions[player] = finalPos
        boardView.positions = positions

        var message = baseMessage
        if (hasRide) {
            UlarTanggaView.LADDERS[landedPos]?.let { top ->
                message = "Pemain ${player + 1} (${playerNames[player]}): TANGGA! $landedPos → $top"
            }
            UlarTanggaView.SNAKES[landedPos]?.let { tail ->
                message = "Pemain ${player + 1} (${playerNames[player]}): ULAR! $landedPos → $tail"
            }
        }

        if (finalPos == 100) {
            gameOver = true
            statusTextView.text = "Pemain ${player + 1} (${playerNames[player]}) MENANG!"
            rollButton.text = getString(R.string.play_again)
            return
        }

        if (roll == 6) {
            // Extra turn for rolling a 6.
            statusTextView.text = "$message — dapat angka 6, lempar lagi!"
        } else {
            currentPlayer = 1 - player
            boardView.currentPlayer = currentPlayer
            statusTextView.text = "$message — giliran Pemain ${currentPlayer + 1}."
        }
    }

    /**
     * Animate the dice "rolling": it spins a full turn while cycling random
     * faces, then settles on the final [roll] result.
     */
    private fun showDice(roll: Int) {
        // Cancel any previous roll animation before starting a new one.
        diceImage.removeCallbacks(faceTick)
        diceImage.animate().cancel()
        diceAnimationRunning = true

        diceImage.postDelayed(faceTick, 70L)

        diceImage.animate()
            .rotationBy(360f)
            .setDuration(600L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Stop cycling faces and show the final result.
                diceImage.removeCallbacks(faceTick)
                diceImage.rotation = 0f
                diceImage.setImageResource(drawableFor(roll))
                diceAnimationRunning = false
            }
            .start()
    }

    /**
     * Map a dice roll (1..6) to its drawable resource.
     */
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

    /**
     * Reset the board for a new game.
     */
    private fun resetGame() {
        positions = intArrayOf(0, 0)
        currentPlayer = 0
        gameOver = false
        tokenAnimating = false
        boardView.positions = positions
        boardView.currentPlayer = currentPlayer
        rollButton.text = getString(R.string.roll_snakes)
        statusTextView.text = getString(R.string.turn_start)
    }
}

/**
 * Dice with a fixed number of sides.
 */
class Dice(private val numSides: Int) {

    /**
     * Do a random dice roll and return the result.
     */
    fun roll(): Int {
        return (1..numSides).random()
    }
}
