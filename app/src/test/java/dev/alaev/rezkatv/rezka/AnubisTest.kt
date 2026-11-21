package dev.alaev.rezkatv.rezka

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class AnubisTest {

    @Test
    fun readsChallengeFromChallengePage() {
        val expected = AnubisChallenge(ID, RANDOM_DATA, DIFFICULTY)
        assertThat("задача из страницы проверки", Anubis.challenge(page()), equalTo(expected))
    }

    @Test
    fun findsNoChallengeOnRealPage() {
        val plain = "<html><body></body></html>"
        assertThat("на обычной странице задачи нет", Anubis.challenge(plain), equalTo(null))
    }

    @Test
    fun solvesChallengeWithLeadingZeros() {
        val challenge = Anubis.challenge(page())!!
        val solved = Anubis.solve(challenge.randomData, challenge.difficulty)
        val expected = sha256(challenge.randomData + solved.first)
        assertThat("хеш считается от randomData и nonce", solved.second, equalTo(expected))
    }

    @Test
    fun solvesOddDifficulty() {
        val solved = Anubis.solve("abc", ODD_DIFFICULTY)
        val expected = "0".repeat(ODD_DIFFICULTY)
        val head = solved.second.take(expected.length)
        assertThat("три нуля при нечётной сложности", head, equalTo(expected))
    }

    private fun page() = javaClass.getResource("/anubis/challenge.html")!!.readText()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ID = "01a18e43-2b1c-72c2-969c-43d8bf443eb3"
        const val DIFFICULTY = 2
        const val ODD_DIFFICULTY = 3
        const val RANDOM_DATA =
            "734e1737ee671876c74d6441074434cce7e79ecd2d9ce7822a08d7c6316bd6cc" +
                "5c56ff9c2580ad4161c695cf5d1b0928689c154342ecc5014e2714d7b8f94e82"
    }
}
