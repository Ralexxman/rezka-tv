package dev.alaev.rezkatv.rezka.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

class CacheProgressTest {

    @Test
    fun showsNoProgressWithoutRunningPrefetch() {
        val actual = CacheProgress.megabytes(null)
        assertThat("без докачки надписи нет", actual, nullValue())
    }

    @Test
    fun roundsDownloadedBytesToWholeMegabytes() {
        val expected = 5L
        val actual = CacheProgress.megabytes(5L * 1024 * 1024 + 1)
        assertThat("целые мегабайты с округлением вниз", actual, equalTo(expected))
    }

    @Test
    fun clampsNegativeAmountToZero() {
        val expected = 0L
        val actual = CacheProgress.megabytes(-4096)
        assertThat("отрицательный счёт показывается как ноль", actual, equalTo(expected))
    }
}
