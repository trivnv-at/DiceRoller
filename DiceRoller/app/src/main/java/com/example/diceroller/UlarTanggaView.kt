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
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import com.google.android.material.R.attr as m3attr
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
            2 to 38, 7 to 14, 8 to 31, 15 to 26, 21 to 42,
            28 to 84, 36 to 44, 51 to 67, 71 to 91, 78 to 98, 87 to 94
        )

        /**
         * Landing on the head of a snake slides down to its tail.
         */
        val SNAKES = mapOf(
            16 to 6, 46 to 25, 49 to 11, 62 to 19, 64 to 60,
            74 to 53, 89 to 68, 92 to 88, 95 to 75, 99 to 80
        )

        /** Warna badan ular (board.html): hijau, merah, ungu, pink. */
        val SNAKE_COLORS = intArrayOf(
            0xFF80C040.toInt(), 0xFFD43A2F.toInt(), 0xFF9070B0.toInt(), 0xFFF06A9A.toInt()
        )
    }

    /** What the token is currently animating through. */
    private enum class AnimKind { NONE, MOVE, RIDE }

    // ---- Paint & warna (Material 3 color roles) ----
    // Warna diselesaikan SEKALI di init() dari theme (bukan onDraw tiap frame)
    // agar performa tetap & mengikuti tema: dynamic color (API 31+), brand
    // fallback (<31), serta light/dark (DayNight). Hex lama diganti role:
    //   grid=colorOutline, sel A/B=surface/surfaceVariant, angka=onSurfaceVariant,
    //   tangga=tertiary, ular/tokenP1=error, tokenP2=primary, outline=surface,
    //   ring=primary, start/goal=primaryContainer.
    private val gridPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val cellFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val numberPaint = Paint().apply { textAlign = Paint.Align.CENTER }
    private val ladderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    private val ladderShadowPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 13f; strokeCap = Paint.Cap.ROUND
    }
    private val snakePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    private val snakeShadowPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND
    }
    // Paint 3D: tangga kayu (gradien silinder) & ular (badan gradien + gloss + kepala).
    private val ladderRailPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ladderRungPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val snakeBodyPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val snakeGlossPaint = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val snakeHeadPaint = Paint().apply { style = Paint.Style.FILL }
    private val snakeHeadOutline = Paint().apply { style = Paint.Style.STROKE }
    private val snakeEyeWhitePaint = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val snakeEyePupilPaint = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(26, 10, 10) }
    // Paint gaya board.html (flat 2-stroke): outline hitam badan ular + lidah + outline tangga.
    private val snakeOutlinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF100000.toInt()
    }
    private val snakeTonguePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFE2553F.toInt()
    }
    private val ladderRailOutline = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF6B4528.toInt()
    }
    private val ladderRungOutline = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFB8860B.toInt()
    }
    private val playerPaints = listOf(
        Paint().apply { style = Paint.Style.FILL }, // player 1 (error)
        Paint().apply { style = Paint.Style.FILL }  // player 2 (primary)
    )
    private val tokenOutline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val highlightPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val highlightHaloPaint = Paint().apply { style = Paint.Style.FILL }
    private val tokenShadowPaint = Paint().apply { style = Paint.Style.FILL }
    private val tokenGlossPaint = Paint().apply { style = Paint.Style.FILL }
    private val boardPanelPaint = Paint().apply { style = Paint.Style.FILL }

    // Warna role hasil resolve (dipakai di onDraw).
    private var cellFillAColor = 0
    private var cellFillBColor = 0
    private var startCellColor = 0xFF3FAE5A.toInt() // sel 1 (hijau)
    private var goalCellColor = 0xFFF2A63C.toInt() // sel 100 (emas)
    private var boardPanelColor = 0
    private var boardPanelGradEnd = 0
    private var lastDrawW = -1

    // Radius sudut sel rounded (fraksi lebar sel) — redesign.
    private val cornerRadiusRatio = 0.12f

    init {
        val ta = context.theme.obtainStyledAttributes(intArrayOf(
            m3attr.colorOutline,                 // 0 grid
            m3attr.colorSurface,                 // 1 sel A
            m3attr.colorSurfaceVariant,          // 2 sel B
            m3attr.colorOnSurfaceVariant,        // 3 angka
            m3attr.colorTertiary,                // 4 tangga
            m3attr.colorError,                   // 5 ular + token P1
            m3attr.colorPrimary,                 // 6 token P2 + ring aktif
            m3attr.colorSurface,                 // 7 outline token
            m3attr.colorPrimaryContainer,        // 8 sel start/goal
            m3attr.colorSurfaceVariant           // 9 panel papan
        ))
        val fb = 0xFF8F5C00.toInt()
        gridPaint.color = ta.getColor(0, fb)
        cellFillAColor = ta.getColor(1, fb)
        cellFillBColor = ta.getColor(2, fb)
        numberPaint.color = ta.getColor(3, fb)
        ladderPaint.color = ta.getColor(4, fb)
        snakePaint.color = ta.getColor(5, fb)
        playerPaints[0].color = ta.getColor(5, fb)
        playerPaints[1].color = ta.getColor(6, fb)
        tokenOutline.color = ta.getColor(7, fb)
        highlightPaint.color = ta.getColor(6, fb)
        boardPanelColor = ta.getColor(9, fb)
        ta.recycle()

        // Gaya board.html: sel putih/krem (light) / gelap (dark), angka & grid ikut tema.
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) {
            cellFillAColor = 0xFF10151F.toInt()
            cellFillBColor = 0xFF1A2230.toInt()
            numberPaint.color = 0xFFAEB7C5.toInt()
            gridPaint.color = 0x12FFFFFF.toInt()
        } else {
            cellFillAColor = 0xFFFFFFFF.toInt()
            cellFillBColor = 0xFFFAF5EA.toInt()
            numberPaint.color = 0xFF9A7B55.toInt()
            gridPaint.color = 0xFFEEE7D6.toInt()
        }
        snakeHeadOutline.color = 0xFF100000.toInt() // outline kepala ular = hitam (board.html)

        // Turunan translusen utk depth/shadow/gloss.
        tokenShadowPaint.color = Color.argb(90, 0, 0, 0)
        tokenGlossPaint.color = Color.argb(100, 255, 255, 255)
        highlightHaloPaint.color = withAlpha(highlightPaint.color, 0.25f)
        boardPanelGradEnd = shade(boardPanelColor, 0.90f, 1f)
    }

    /** Interpolasi linear dua warna (t=0 → [from], t=1 → [to]). */
    private fun lerp(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt().coerceIn(0, 255)
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /** Argba dari [color] dgn alpha diskala [alphaFrac] (0..1). */
    private fun withAlpha(color: Int, alphaFrac: Float): Int {
        val a = (Color.alpha(color) * alphaFrac).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /** Versi gelap + translusen dari [color] utk depth/shadow. */
    private fun shade(color: Int, factor: Float, alphaFrac: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        val a = (Color.alpha(color) * alphaFrac).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
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
    private var lastHopPlayed = -1  // hop terakhir yg sudah bersuara (per MOVE)

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
        lastHopPlayed = -1
        if (hasRide) {
            // Suara slide tangga/ular (naik / turun) sesuai arah ride.
            if (rideTo > rideFrom) SoundPlayer.ladder() else SoundPlayer.snake()
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
                // Hop per-kotak (MOVE): bunyi hop + getar tiap token masuk kotak baru.
                if (kind == AnimKind.MOVE) {
                    val steps = (to - from).coerceAtLeast(1)
                    val hopIdx = (animProgress * steps).toInt().coerceIn(0, steps - 1)
                    if (hopIdx != lastHopPlayed) {
                        lastHopPlayed = hopIdx
                        SoundPlayer.hop()
                        SoundPlayer.vibrateShort()
                    }
                }
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
        SoundPlayer.land() // landing squash setelah slide tangga/ular
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
     * Draw the 10x10 grid with rounded alternating cell fills, small square
     * numbers, and a softly-gradiented panel behind. Start (1) & goal (100)
     * cells are highlighted with primaryContainer.
     */
    private fun drawBoard(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        val bw = 10 * sq
        val corner = sq * cornerRadiusRatio

        // Panel papan (background) rounded dgn gradien halus — cache shader per lebar.
        if (boardPanelPaint.shader == null || lastDrawW != width) {
            boardPanelPaint.shader = LinearGradient(
                0f, oy, 0f, oy + bw,
                boardPanelColor, boardPanelGradEnd, Shader.TileMode.CLAMP
            )
            lastDrawW = width
        }
        canvas.drawRoundRect(ox, oy, ox + bw, oy + bw, sq * 0.6f, sq * 0.6f, boardPanelPaint)

        for (row in 0 until 10) {
            for (col in 0 until 10) {
                val left = ox + col * sq
                val top = oy + row * sq
                val squareNumber = squareNumberAt(row, col)
                cellFillPaint.color = when {
                    squareNumber == 1 -> startCellColor // sel 1 (hijau)
                    squareNumber == 100 -> goalCellColor // sel 100 (emas)
                    squareNumber % 2 == 0 -> cellFillBColor
                    else -> cellFillAColor
                }
                canvas.drawRoundRect(left, top, left + sq, top + sq, corner, corner, cellFillPaint)
                canvas.drawRoundRect(left, top, left + sq, top + sq, corner, corner, gridPaint)

                numberPaint.textSize = sq * 0.2f
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
     * Draw the snakes in the board.html style: colored curved bodies with a dark
     * outline, round head with black eyes and a red forked tongue.
     */
    private fun drawSnakes(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        val bodyW = sq * 0.32f
        snakeBodyPaint.strokeWidth = bodyW
        snakeOutlinePaint.strokeWidth = sq * 0.46f
        snakeHeadOutline.strokeWidth = sq * 0.1f
        var si = 0
        for ((head, tail) in SNAKES) {
            val (hx, hy) = squareCenter(head, sq, ox, oy)
            val (tx, ty) = squareCenter(tail, sq, ox, oy)
            val dx = tx - hx
            val dy = ty - hy
            val midX = (hx + tx) / 2f + dy * 0.2f
            val midY = (hy + ty) / 2f - dx * 0.2f
            val path = Path().apply { moveTo(hx, hy); quadTo(midX, midY, tx, ty) }

            // Badan: outline hitam tebal di bawah, warna di atas.
            canvas.drawPath(path, snakeOutlinePaint)
            val col = SNAKE_COLORS[si % SNAKE_COLORS.size]
            snakeBodyPaint.color = col
            canvas.drawPath(path, snakeBodyPaint)

            // Kepala bulat (warna sama) + outline hitam.
            val ang = Math.atan2((midY - hy).toDouble(), (midX - hx).toDouble())
            val cos = Math.cos(ang).toFloat()
            val sin = Math.sin(ang).toFloat()
            val headR = sq * 0.3f
            snakeHeadPaint.color = col
            canvas.drawCircle(hx, hy, headR, snakeHeadPaint)
            canvas.drawCircle(hx, hy, headR, snakeHeadOutline)

            // Lidah bercabang merah dari ujung kepala.
            val lx = hx + cos * sq * 0.4f
            val ly = hy + sin * sq * 0.4f
            val b1x = cos * sq * 0.14f + Math.cos(ang + 0.5).toFloat() * sq * 0.1f
            val b1y = sin * sq * 0.14f + Math.sin(ang + 0.5).toFloat() * sq * 0.1f
            val b2x = cos * sq * 0.14f + Math.cos(ang - 0.5).toFloat() * sq * 0.1f
            val b2y = sin * sq * 0.14f + Math.sin(ang - 0.5).toFloat() * sq * 0.1f
            snakeTonguePaint.strokeWidth = sq * 0.08f
            canvas.drawLine(lx, ly, lx + b1x, ly + b1y, snakeTonguePaint)
            canvas.drawLine(lx, ly, lx + b2x, ly + b2y, snakeTonguePaint)

            // Mata (2 titik hitam).
            val er = sq * 0.07f
            snakeEyePupilPaint.color = 0xFF100000.toInt()
            canvas.drawCircle(
                hx + Math.cos(ang + 0.5).toFloat() * sq * 0.13f,
                hy + Math.sin(ang + 0.5).toFloat() * sq * 0.13f, er, snakeEyePupilPaint
            )
            canvas.drawCircle(
                hx + Math.cos(ang - 0.5).toFloat() * sq * 0.13f,
                hy + Math.sin(ang - 0.5).toFloat() * sq * 0.13f, er, snakeEyePupilPaint
            )
            si++
        }
    }

    /**
     * Draw the ladders in the board.html style: flat brown rails + gold rungs,
     * each with a dark outline (2-stroke, no cylinder shading).
     */
    private fun drawLadders(canvas: Canvas, sq: Float, ox: Float, oy: Float) {
        val railW = sq * 0.17f
        ladderRailPaint.strokeWidth = railW
        ladderRailPaint.color = 0xFFA57949.toInt()
        ladderRailOutline.strokeWidth = sq * 0.29f
        val rungW = sq * 0.15f
        ladderRungPaint.strokeWidth = rungW
        ladderRungPaint.color = 0xFFF0C000.toInt()
        ladderRungOutline.strokeWidth = sq * 0.26f
        for ((bottom, top) in LADDERS) {
            val (bx, by) = squareCenter(bottom, sq, ox, oy)
            val (tx, ty) = squareCenter(top, sq, ox, oy)
            val dx = tx - bx
            val dy = ty - by
            val len = hypot(dx, dy).toFloat().coerceAtLeast(1f)
            // Perpendicular unit vector to offset the two rails.
            val px = -dy / len * sq * 0.18f
            val py = dx / len * sq * 0.18f

            // Rel: outline coklat tua di bawah, isi coklat di atas.
            canvas.drawLine(bx + px, by + py, tx + px, ty + py, ladderRailOutline)
            canvas.drawLine(bx + px, by + py, tx + px, ty + py, ladderRailPaint)
            canvas.drawLine(bx - px, by - py, tx - px, ty - py, ladderRailOutline)
            canvas.drawLine(bx - px, by - py, tx - px, ty - py, ladderRailPaint)

            // Anak tangga: outline emas gelap di bawah, isi emas di atas.
            for (i in 1 until 4) {
                val f = i.toFloat() / 4f
                val rx = bx + (tx - bx) * f
                val ry = by + (ty - by) * f
                canvas.drawLine(rx + px, ry + py, rx - px, ry - py, ladderRungOutline)
                canvas.drawLine(rx + px, ry + py, rx - px, ry - py, ladderRungPaint)
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
                drawTokenBody(canvas, cx, ty, rx, ry, p, paint)
            } else {
                drawTokenBody(canvas, cx, ty, r, r, p, paint)
            }
            if (p == currentPlayer) {
                // Ring ikut bentuk token: pakai rx (diskala) saat squash, else r.
                val ringR = if (p == squashPlayer) r * squashScaleX else r
                canvas.drawCircle(cx, ty, ringR + highlightPaint.strokeWidth, highlightPaint)
            }
        }
    }

    /**
     * Gambar satu token: halo ring aktif (di belakang), drop shadow (under-draw,
     * menghindari setShadowLayer/software layer), body oval + outline, lalu gloss
     * kecil di kuadran atas. Tetap mengikuti squash/stretch via [rx]/[ry].
     */
    private fun drawTokenBody(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        p: Int,
        fill: Paint
    ) {
        if (p == currentPlayer) {
            canvas.drawCircle(cx, cy, rx + highlightPaint.strokeWidth * 0.6f, highlightHaloPaint)
        }
        // Drop shadow: lingkaran gelap translusen, offset ke bawah.
        canvas.drawCircle(cx, cy + rx * 0.14f, rx, tokenShadowPaint)
        // Body.
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, tokenOutline)
        // Gloss: lingkaran kecil putih translusen di kuadran atas-kiri.
        canvas.drawCircle(cx - rx * 0.28f, cy - ry * 0.42f, rx * 0.16f, tokenGlossPaint)
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
