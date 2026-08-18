package com.example.diceroller

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client HTTP ke backend ular-tangga (server-authoritative).
 *
 * Android berperan sebagai Player 1 (host): buat game, polling state, kirim
 * roll. Semua fungsi harus dipanggil dari background thread — MainActivity
 * yang mengelola `thread {}` + `runOnUiThread`.
 *
 * Kontrak JSON mengikuti backend Express (lihat backend/src/http/routes.ts).
 * Pakai HttpURLConnection + org.json (bawaan Android SDK) = tanpa dependency baru.
 */
object GameApi {

    // Base URL backend live (nginx di port 80). Ganti bila pindah server.
    const val BASE = "http://51.21.221.31"

    /** Kredensial + identitas Player 1 setelah create/join. */
    data class PlayerCreds(
        val gameId: String,
        val gameCode: String,
        val playerId: String,
        val secret: String,
        val seat: Int,
    )

    /** Hasil roll terakhir (untuk animasi dadu & token). */
    data class LastRoll(
        val seat: Int,
        val value: Int,
        val from: Int,
        val to: Int,
        val rideFrom: Int?,
        val rideTo: Int?,
    )

    /** State game dari server (server-authoritative). */
    data class GameView(
        val status: String,        // "waiting" | "playing" | "finished"
        val positions: IntArray,   // index seat: [p1, p2]
        val currentPlayer: Int,
        val winner: Int?,          // seat pemenang, null jika belum
        val lastRoll: LastRoll?,
    )

    /** Host buat game baru. Player 1 = seat 0. */
    fun createGame(name: String): PlayerCreds {
        val root = JSONObject(request("POST", "/games", "{\"name\":\"${name.escapeJson()}\"}"))
        return PlayerCreds(
            gameId = root.getString("gameId"),
            gameCode = root.getString("gameCode"),
            playerId = root.getString("playerId"),
            secret = root.getString("secret"),
            seat = root.getInt("seat"),
        )
    }

    /** Polling state game. */
    fun getGame(creds: PlayerCreds): GameView {
        val body = request("GET", "/games/${creds.gameId}", null)
        return parseGame(JSONObject(body).getJSONObject("game"))
    }

    /** Roll dadu — dadu dibuat & divalidasi server, hasil dipakai untuk animasi. */
    fun roll(creds: PlayerCreds): GameView {
        val body = request(
            method = "POST",
            path = "/games/${creds.gameId}/roll",
            jsonBody = null,
            headers = authHeaders(creds),
        )
        return parseGame(JSONObject(body).getJSONObject("game"))
    }

    /** Reset / rematch. */
    fun reset(creds: PlayerCreds): GameView {
        val body = request(
            method = "POST",
            path = "/games/${creds.gameId}/reset",
            jsonBody = null,
            headers = authHeaders(creds),
        )
        return parseGame(JSONObject(body).getJSONObject("game"))
    }

    private fun authHeaders(creds: PlayerCreds): Map<String, String> = mapOf(
        "X-Player-Id" to creds.playerId,
        "X-Player-Secret" to creds.secret,
    )

    /**
     * Jalankan request HTTP dan kembalikan body respons sebagai String.
     * Lempar RuntimeException bila status != 2xx.
     */
    private fun request(
        method: String,
        path: String,
        jsonBody: String?,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (jsonBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

            if (code !in 200..299) {
                throw RuntimeException("HTTP $code $path: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Parse objek `game` (view) menjadi [GameView]. */
    private fun parseGame(g: JSONObject): GameView {
        val pos = g.getJSONObject("positions")
        val lr = g.optJSONObject("lastRoll")
        val lastRoll = if (lr != null) {
            LastRoll(
                seat = lr.getInt("seat"),
                value = lr.getInt("value"),
                from = lr.getInt("from"),
                to = lr.getInt("to"),
                rideFrom = if (lr.isNull("rideFrom")) null else lr.optInt("rideFrom", -1).let { if (it < 0) null else it },
                rideTo = if (lr.isNull("rideTo")) null else lr.optInt("rideTo", -1).let { if (it < 0) null else it },
            )
        } else {
            null
        }
        return GameView(
            status = g.getString("status"),
            positions = intArrayOf(pos.getInt("p1"), pos.getInt("p2")),
            currentPlayer = g.getInt("currentPlayer"),
            winner = if (g.isNull("winner")) null else g.getInt("winner"),
            lastRoll = lastRoll,
        )
    }

    /** Escape kutip & backslash agar aman disisipkan ke JSON body. */
    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
