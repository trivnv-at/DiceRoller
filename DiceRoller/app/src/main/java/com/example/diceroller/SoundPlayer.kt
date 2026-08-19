package com.example.diceroller

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Pemutar efek suara procedural (file WAV di res/raw, hasil gen-sounds.mjs)
 * + haptic ringan.
 *
 * Object singleton agar `MainActivity` (roll/win) & `UlarTanggaView` (hop/land
 * tiap kotak) memakai instance yang sama. Semua `play*` aman dipanggil kapan
 * saja: bila sound belum ter-load (0) SoundPool mengabaikannya.
 */
object SoundPlayer {

    private var pool: SoundPool? = null
    private var vibrator: Vibrator? = null

    // soundId hasil SoundPool.load — 0 = belum ter-load.
    private var sRoll = 0
    private var sHop = 0
    private var sLand = 0
    private var sLadder = 0
    private var sSnake = 0
    private var sWin = 0

    /** Panggil sekali (mis. di Activity.onCreate) sebelum memainkan suara. */
    fun init(context: Context) {
        if (pool != null) return // sudah di-init
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } else {
            null
        }
        sRoll = pool!!.load(context, R.raw.dice_roll, 1)
        sHop = pool!!.load(context, R.raw.token_hop, 1)
        sLand = pool!!.load(context, R.raw.token_land, 1)
        sLadder = pool!!.load(context, R.raw.ladder, 1)
        sSnake = pool!!.load(context, R.raw.snake, 1)
        sWin = pool!!.load(context, R.raw.win, 1)
    }

    private fun play(id: Int) {
        val p = pool ?: return
        if (id == 0) return
        p.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun roll() = play(sRoll)
    fun hop() = play(sHop)
    fun land() = play(sLand)
    fun ladder() = play(sLadder)
    fun snake() = play(sSnake)
    fun win() = play(sWin)

    /** Getar pendek untuk hop / roll (12ms). */
    fun vibrateShort() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(12)
        }
    }

    /** Getar lebih lama untuk menang (200ms). */
    fun vibrateWin() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(200)
        }
    }

    /** Lepas sumber daya (opsional, di onDestroy). */
    fun release() {
        pool?.release()
        pool = null
    }
}
