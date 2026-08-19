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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

/**
 * Custom View that draws a classic 10x10 Snakes and Ladders board (100 squares)
 * with ladders, snakes, and two player tokens.
 */
class UlarTanggaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * Classic layout: landing on the bottom of a ladder climbs to the top.
     */
    companion object {
        val LADDERS = mapOf(
            1 to 38, 4 to 14, 9 to 31, 21 to 42, 28 to 84,
            36 to 44, 51 to 67, 71 to 91, 80 to 100
        )

        /**
         * Landing on the head of a snake slides down to its tail.
         */
        val SNAKES = mapOf(
            16 to 6, 47 to 26, 49 to 11, 56 to 53, 62 to 19,
            64 to 60, 87 to 24, 93 to 73, 95 to 75, 98 to 78
        )
    }

    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#455A64")
    }
    private val cellFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val numberPaint = Paint().apply {
        color = Color.parseColor("#78909C")
        textAlign = Paint.Align.CENTER
    }
    private val ladderPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val snakePaint = Paint().apply {
        color = Color.parseColor("#E53935")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val playerPaints = listOf(
        Paint().apply { color = Color.parseColor("#E53935") }, // player 1 red
        Paint().apply { color = Color.parseColor("#1E88E5") }  // player 2 blue
    )
    private val tokenOutline = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
    }
    // Bright ring drawn around the token of the active player.
    private val highlightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#FFC107")
    }

    /**
     * Current board positions of the two players (1..100, or 0 = not started).
     * Assigning a new value redraws the board.
     */
    var positions = intArrayOf(0, 0)
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Index of the player whose turn it is; their token gets a highlight ring.
     */
    var currentPlayer = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Size the 10x10 board so it fits the view and leaves a margin for the
        // "not started" tokens on the left.
        val sq = minOf(width, height) / 12f
        val ox = (width - 10 * sq) / 2f
        val oy = (height - 10 * sq) / 2f

        drawBoard(canvas, sq, ox, oy)
        drawSnakes(canvas, sq, ox, oy)
        drawLadders(canvas, sq, ox, oy)
        drawTokens(canvas, sq, ox, oy)
    }

    /**
     * Draw the 10x10 grid with alternating cell fills and small square numbers.
     */
    private fun drawBoard(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                val left = ox + col * sq
                val top = oy + row * sq
                cellFillPaint.color = if ((row + col) % 2 == 0) {
                    Color.parseColor("#FFF3E0")
                } else {
                    Color.parseColor("#FFE0B2")
                }
                canvas.drawRect(left, top, left + sq, top + sq, cellFillPaint)
                canvas.drawRect(left, top, left + sq, top + sq, gridPaint)

                numberPaint.textSize = sq * 0.2f
                val squareNumber = squareNumberAt(row, col)
                canvas.drawText(
                    squareNumber.toString(),
                    left + sq / 2f,
                    top + sq * 0.3f,
                    numberPaint
                )
            }
        }
    }

    /**
     * Map a grid (row, col) to the square number using the zigzag path where
     * square 1 is the bottom-left and the path winds upward.
     */
    private fun squareNumberAt(row: Int, col: Int): Int {
        val rowFromBottom = 9 - row
        val c = if (rowFromBottom % 2 == 1) 9 - col else col
        return rowFromBottom * 10 + c + 1
    }

    /**
     * Center pixel of a square, given its square number (1..100).
     */
    private fun squareCenter(square: Int, sq: Float, ox: Float, oy: Float): Pair<Float, Float> {
        val idx = square - 1
        val rowFromBottom = idx / 10
        var col = idx % 10
        if (rowFromBottom % 2 == 1) col = 9 - col
        val cx = ox + col * sq + sq / 2f
        val cy = oy + (9 - rowFromBottom) * sq + sq / 2f
        return cx to cy
    }

    /**
     * Draw the snakes as red curved lines from head to tail.
     */
    private fun drawSnakes(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        for ((head, tail) in SNAKES) {
            val (hx, hy) = squareCenter(head, sq, ox, oy)
            val (tx, ty) = squareCenter(tail, sq, ox, oy)
            val path = Path().apply {
                moveTo(hx, hy)
                // Control point offset perpendicular to the head->tail line for a curve.
                val dx = tx - hx
                val dy = ty - hy
                val midX = (hx + tx) / 2f + dy * 0.2f
                val midY = (hy + ty) / 2f - dx * 0.2f
                quadTo(midX, midY, tx, ty)
            }
            canvas.drawPath(path, snakePaint)
        }
    }

    /**
     * Draw the ladders as green parallel rails with rungs from bottom to top.
     */
    private fun drawLadders(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        for ((bottom, top) in LADDERS) {
            val (bx, by) = squareCenter(bottom, sq, ox, oy)
            val (tx, ty) = squareCenter(top, sq, ox, oy)
            val dx = tx - bx
            val dy = ty - by
            val len = hypot(dx, dy).toFloat().coerceAtLeast(1f)
            // Perpendicular unit vector to offset the two rails.
            val px = -dy / len * sq * 0.18f
            val py = dx / len * sq * 0.18f

            canvas.drawLine(bx + px, by + py, tx + px, ty + py, ladderPaint)
            canvas.drawLine(bx - px, by - py, tx - px, ty - py, ladderPaint)

            // Draw a few rungs between the rails.
            val rungs = 4
            for (i in 1 until rungs) {
                val f = i.toFloat() / rungs
                val rx = bx + (tx - bx) * f
                val ry = by + (ty - by) * f
                canvas.drawLine(rx + px, ry + py, rx - px, ry - py, ladderPaint)
            }
        }
    }

    /**
     * Draw the two player tokens as colored circles, applying any current
     * movement/ride/bounce animation and the active-player highlight ring.
     */
    private fun drawTokens(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        val r = sq * 0.3f
        for (p in positions.indices) {
            val paint = playerPaints[p]
            val (cx, cy) = tokenCenterForSquare(positions[p], p, sq, ox, oy, r)
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx, cy, r, tokenOutline)
            if (p == currentPlayer) {
                canvas.drawCircle(cx, cy, r + highlightPaint.strokeWidth, highlightPaint)
            }
        }
    }

    /**
     * Center pixel of a token resting on [square] (or in the left margin when
     * the token has not started, square 0). Tokens sharing a square are offset
     * slightly so both stay visible.
     */
    private fun tokenCenterForSquare(
        square: Int,
        p: Int,
        sq: Float,
        ox: Float,
        oy: Float,
        r: Float
    ): Pair<Float, Float> {
        if (square == 0) {
            val (sq1x, sq1y) = squareCenter(1, sq, ox, oy)
            return (sq1x - sq * 0.6f - p * r * 1.4f) to sq1y
        }
        val (x, y) = squareCenter(square, sq, ox, oy)
        val off = if (p == 0) -r * 0.5f else r * 0.5f
        return (x + off) to y
    }

}
