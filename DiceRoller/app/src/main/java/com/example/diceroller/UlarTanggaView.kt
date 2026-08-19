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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Easing masuk-keluar halus (Penner cubic ease-in-out) — mirror `easeInOut`
 * di web (`web/src/components/Board.tsx`). Dipakai utk pergerakan token MOVE
 * & RIDE agar sinkron dgn web.
 */
private class EaseInOutInterpolator : Interpolator {
    override fun getInterpolation(input: Float): Float {
        return easeCubic(input)
    }
}

/** Cubic ease-in-out utk per-hop MOVE — mirror `easeInOut` di web Board.tsx. */
private fun easeCubic(t: Float): Float {
    val d = t.toDouble()
    val r = if (d < 0.5) 4 * d * d * d else 1 - (-2 * d + 2).pow(3.0) / 2
    return r.toFloat()
}

/**
 * Custom View that draws a classic 10x10 Snakes and Ladders board (100 squares)
 * with ladders, snakes, and two player tokens. Tokens can be animated moving
 * along the zigzag path, riding ladders/snakes, and bouncing on arrival.
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

    /** What the token is currently animating through. */
    private enum class AnimKind { NONE, MOVE, RIDE }

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

    // ---- animation state ----
    private var animPlayer = -1
    private var animFrom = 0
    private var animTo = 0
    private var animProgress = 0f
    private var animKind = AnimKind.NONE
    private var animator: ValueAnimator? = null
    private var animDuration = 0L   // durasi penuh ValueAnimator (MOVE = WALK + LAND_MS)
    private var animIsWin = false   // squash besar saat hop terakhir menang (mirror web)

    // Landing squash & stretch (seragam dgn web): konstanta sinkron LAND_MS/DEPTH.
    private val LAND_MS = 140L
    private val DEPTH = 0.18f
    private val WIN_DEPTH = 0.30f
    private val ARC_RATIO = 0.2f    // tinggi busur hop = 0.2 × lebar kotak (web: ARC)
    private var squashPlayer = -1
    private var squashScaleX = 1f
    private var squashScaleY = 1f

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
     * Play a player's full move: walk [from]..[rideFrom] square by square (dice
     * steps only go as far as the base of a ladder / head of a snake), then
     * optionally ride a ladder/snake from [rideFrom] to [rideTo], bounce on
     * arrival, then invoke [onDone].
     */
    fun playPlayerMove(
        player: Int,
        from: Int,
        rideFrom: Int,
        rideTo: Int,
        hasRide: Boolean,
        isWin: Boolean,
        onDone: () -> Unit
    ) {
        stopAnimator()
        animIsWin = isWin
        if (hasRide) {
            // MOVE per-hop (squash inline tiap hop), lalu RIDE slide lurus, lalu
            // squash sekali saat mendarat tujuan tangga/ular.
            animateBetween(player, from, rideFrom, AnimKind.MOVE, 550L) {
                animateBetween(player, rideFrom, rideTo, AnimKind.RIDE, 700L) {
                    squash(player, isWin, onDone)
                }
            }
        } else {
            // MOVE per-hop sudah memuat squash hop terakhir (mirror web) — onDone
            // dipanggil setelah squash terakhir selesai di akhir MOVE.
            animateBetween(player, from, rideFrom, AnimKind.MOVE, 550L, onDone)
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        animPlayer = -1
        animKind = AnimKind.NONE
        squashPlayer = -1
        squashScaleX = 1f
        squashScaleY = 1f
    }

    private fun animateBetween(
        player: Int,
        from: Int,
        to: Int,
        kind: AnimKind,
        duration: Long,
        onDone: () -> Unit
    ) {
        animPlayer = player
        animFrom = from
        animTo = to
        animKind = kind
        animProgress = 0f
        // MOVE: durasi ditambah LAND_MS agar squash hop terakhir sempat selesai
        // (sama dgn web yg berjalan hingga sinceLand >= LAND_MS). RIDE: penuh.
        animDuration = if (kind == AnimKind.MOVE) duration + LAND_MS else duration
        val av = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = animDuration
            // MOVE pakai linear (progress waktu seragam) lalu ease PER-HOP saat
            // render; RIDE pakai ease global (slide lurus). Sinkron dgn web.
            if (kind == AnimKind.RIDE) interpolator = EaseInOutInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animProgress = 1f
                    invalidate()
                    onDone()
                }
            })
            start()
        }
        animator = av
    }

    /** Landing squash & stretch (seragam dgn web): scaleY = 1 - DEPTH*(1-t)^2. */
    private fun squash(player: Int, isWin: Boolean, onDone: () -> Unit) {
        squashPlayer = player
        squashScaleX = 1f
        squashScaleY = 1f
        val depth = if (isWin) WIN_DEPTH else DEPTH
        val av = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = LAND_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                squashScaleY = 1f - depth * (1f - t) * (1f - t)
                squashScaleX = 1f + depth * (1f - t) * (1f - t)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    squashPlayer = -1
                    squashScaleX = 1f
                    squashScaleY = 1f
                    animPlayer = -1
                    animKind = AnimKind.NONE
                    invalidate()
                    onDone()
                }
            })
            start()
        }
        animator = av
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
            val (cx, cy) = if (p == animPlayer) {
                animatedTokenCenter(p, sq, ox, oy, r)
            } else {
                tokenCenterForSquare(positions[p], p, sq, ox, oy, r)
            }
            val ty = cy
            if (p == squashPlayer) {
                // squash/stretch landing: oval dgn radius diskala, pusat tetap.
                val rx = r * squashScaleX
                val ry = r * squashScaleY
                canvas.drawOval(cx - rx, ty - ry, cx + rx, ty + ry, paint)
                canvas.drawOval(cx - rx, ty - ry, cx + rx, ty + ry, tokenOutline)
            } else {
                canvas.drawCircle(cx, ty, r, paint)
                canvas.drawCircle(cx, ty, r, tokenOutline)
            }
            if (p == currentPlayer) {
                // Ring ikut bentuk token: pakai rx (diskala) saat squash, else r.
                val ringR = if (p == squashPlayer) r * squashScaleX else r
                canvas.drawCircle(cx, ty, ringR + highlightPaint.strokeWidth, highlightPaint)
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

    /**
     * Center pixel of the token currently being animated, interpolated along
     * the zigzag path (MOVE) or in a straight line (RIDE).
     */
    private fun animatedTokenCenter(
        p: Int,
        sq: Float,
        ox: Float,
        oy: Float,
        r: Float
    ): Pair<Float, Float> {
        val from = animFrom
        val to = animTo
        return if (animKind == AnimKind.RIDE) {
            lerp(
                tokenCenterForSquare(from, p, sq, ox, oy, r),
                tokenCenterForSquare(to, p, sq, ox, oy, r),
                animProgress
            )
        } else {
            // MOVE per-hop: progress waktu seragam (linear), tiap hop di-ease
            // cubic ease-in-out sendiri + busur parabola — mirror web Board.tsx
            // (hopIdx = floor(elapsed/hopMs); t per-hop; arc = ARC*sin(pi*t)).
            val steps = (to - from).coerceAtLeast(1)
            val hopMs = ((animDuration - LAND_MS).toFloat() / steps).coerceAtLeast(1f)
            val elapsed = animProgress * animDuration
            val hopIdx = floor(elapsed / hopMs).toInt().coerceIn(0, steps - 1)
            val t = ((elapsed - hopIdx * hopMs) / hopMs).coerceIn(0f, 1f)
            val e = easeCubic(t)  // ease per-hop (cubic ease-in-out), sinkron web
            val a = from + hopIdx
            val b = from + hopIdx + 1
            val (bx, by) = lerp(
                tokenCenterForSquare(a, p, sq, ox, oy, r),
                tokenCenterForSquare(b, p, sq, ox, oy, r),
                e
            )
            // Hop parabola: busur ARC_RATIO × tile, puncak di tengah hop.
            val arcY = sq * ARC_RATIO * sin(PI * t.toDouble()).toFloat()

            // Squash & stretch saat mendarat di tiap hop (mirror web :266-278).
            val nominal = animDuration - LAND_MS
            val done = elapsed >= nominal
            val landings = minOf(steps, floor(elapsed / hopMs).toInt())
            val sinceLand = elapsed - landings * hopMs
            if (sinceLand >= 0f && sinceLand < LAND_MS) {
                val lt = sinceLand / LAND_MS
                val depth = if (done && animIsWin) WIN_DEPTH else DEPTH
                val d = depth * (1f - lt) * (1f - lt)
                squashScaleX = 1f + d
                squashScaleY = 1f - d
                squashPlayer = animPlayer
            } else {
                squashPlayer = -1
                squashScaleX = 1f
                squashScaleY = 1f
            }
            return bx to (by - arcY)
        }
    }

    private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float): Pair<Float, Float> =
        (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
}
