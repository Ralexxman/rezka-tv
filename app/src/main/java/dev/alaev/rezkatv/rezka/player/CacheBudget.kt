package dev.alaev.rezkatv.rezka.player

object CacheBudget {

    private const val PREFETCH_PART = 2
    private const val STORAGE_PART = 2

    fun allowsPrefetch(fetchedBytes: Long, cacheBytes: Long): Boolean = fetchedBytes < cacheBytes / PREFETCH_PART

    fun cacheBytes(wantedBytes: Long, usedBytes: Long, freeBytes: Long): Long =
        minOf(wantedBytes, (usedBytes + freeBytes) / STORAGE_PART)
}
