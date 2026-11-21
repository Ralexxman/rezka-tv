package dev.alaev.rezkatv.rezka.player

object CacheProgress {

    private const val MB = 1024L * 1024

    fun megabytes(bytes: Long?): Long? = bytes?.let { it.coerceAtLeast(0) / MB }
}
