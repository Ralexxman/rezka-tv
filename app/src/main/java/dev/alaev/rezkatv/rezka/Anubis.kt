package dev.alaev.rezkatv.rezka

import org.json.JSONObject
import java.security.MessageDigest

object Anubis {

    const val PASS_PATH = "/.within.website/x/cmd/anubis/api/pass-challenge"
    private val BLOCK = Regex("""id="anubis_challenge"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private const val HEX = "0123456789abcdef"
    private const val MIN_DIFFICULTY = 1
    private const val LOW_BYTE_MASK = 0xff
    private const val LOW_NIBBLE_MASK = 0xf
    private const val NIBBLE_BITS = 4

    fun challenge(html: String): AnubisChallenge? = BLOCK.find(html)
        ?.groupValues
        ?.get(1)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.let(::parse)

    private fun parse(obj: JSONObject): AnubisChallenge? {
        val data = obj.optJSONObject("challenge") ?: return null
        val difficulty = data.optInt("difficulty", obj.optJSONObject("rules")?.optInt("difficulty") ?: 0)
        val id = data.optString("id")
        val randomData = data.optString("randomData")
        return if (difficulty < MIN_DIFFICULTY || id.isEmpty() || randomData.isEmpty()) {
            null
        } else {
            AnubisChallenge(id, randomData, difficulty)
        }
    }

    fun solve(randomData: String, difficulty: Int): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val data = randomData.toByteArray()
        val fullBytes = difficulty / 2
        val oddNibble = difficulty % 2 != 0
        var nonce = 0L
        while (true) {
            digest.reset()
            digest.update(data)
            val sum = digest.digest(nonce.toString().toByteArray())
            if (sum.passes(fullBytes, oddNibble)) return nonce to sum.toHex()
            nonce++
        }
    }

    private fun ByteArray.passes(fullBytes: Int, oddNibble: Boolean): Boolean {
        for (i in 0 until fullBytes) if (this[i] != 0.toByte()) return false
        return !oddNibble || (this[fullBytes].toInt() shr NIBBLE_BITS) == 0
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and LOW_BYTE_MASK
            out.append(HEX[value shr NIBBLE_BITS]).append(HEX[value and LOW_NIBBLE_MASK])
        }
        return out.toString()
    }
}
