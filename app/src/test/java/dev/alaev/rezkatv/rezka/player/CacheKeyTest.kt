package dev.alaev.rezkatv.rezka.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class CacheKeyTest {

    @Test
    fun matchesSameSegmentFromRedirectedHost() {
        val path = "/0085c6:2026112311:dlF6N1d==/8/0/3/mkpiy.mp4:hls:seg-65-v1-a1.ts"
        val expected = CacheKey.of("https://voidviral.org$path")
        val actual = CacheKey.of("https://phantom.voidviral.org$path")
        assertThat("сегмент с зеркала после перенаправления находится в кэше", actual, equalTo(expected))
    }

    @Test
    fun keepsQueryInKey() {
        val expected = "/seg-1.ts?t=abc"
        val actual = CacheKey.of("https://cdn.example/seg-1.ts?t=abc")
        assertThat("параметры адреса остаются частью ключа", actual, equalTo(expected))
    }

    @Test
    fun separatesDifferentSegments() {
        val expected = false
        val actual = CacheKey.of("https://a.example/seg-1.ts") == CacheKey.of("https://a.example/seg-2.ts")
        assertThat("разные сегменты не смешиваются в кэше", actual, equalTo(expected))
    }
}
