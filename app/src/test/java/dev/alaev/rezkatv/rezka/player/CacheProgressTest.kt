package dev.alaev.rezkatv.rezka.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

class CacheProgressTest {

    @Test
    fun `no running prefetch shows no progress`() {
        assertThat(CacheProgress.megabytes(null), nullValue())
    }

    @Test
    fun `downloaded bytes are shown in whole megabytes rounded down`() {
        assertThat(CacheProgress.megabytes(5L * 1024 * 1024 + 1), equalTo(5L))
    }

    @Test
    fun `cache shrinking under eviction never shows a negative amount`() {
        assertThat(CacheProgress.megabytes(-4096), equalTo(0L))
    }
}
