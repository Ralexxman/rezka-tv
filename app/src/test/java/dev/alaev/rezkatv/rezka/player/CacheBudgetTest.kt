package dev.alaev.rezkatv.rezka.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class CacheBudgetTest {

    @Test
    fun allowsPrefetchBelowHalfOfCache() {
        val cache = 4L * 1024 * 1024 * 1024
        val expected = true
        val actual = CacheBudget.allowsPrefetch(cache / 2 - 1, cache)
        assertThat("докачка идёт, пока скачано меньше половины кэша", actual, equalTo(expected))
    }

    @Test
    fun stopsPrefetchAtHalfOfCache() {
        val cache = 4L * 1024 * 1024 * 1024
        val expected = false
        val actual = CacheBudget.allowsPrefetch(cache / 2, cache)
        assertThat("докачка останавливается на половине кэша, даже если кэш полный", actual, equalTo(expected))
    }

    @Test
    fun keepsChosenSizeWhenStorageIsRoomy() {
        val gb = 1024L * 1024 * 1024
        val expected = 4 * gb
        val actual = CacheBudget.cacheBytes(4 * gb, 2 * gb, 9 * gb)
        assertThat("на просторной приставке кэш как в настройках", actual, equalTo(expected))
    }

    @Test
    fun takesAtMostHalfOfStorageAvailableToCache() {
        val gb = 1024L * 1024 * 1024
        val expected = 3 * gb
        val actual = CacheBudget.cacheBytes(4 * gb, 4 * gb, 2 * gb)
        assertThat("кэш не больше половины места, своего и свободного", actual, equalTo(expected))
    }
}
