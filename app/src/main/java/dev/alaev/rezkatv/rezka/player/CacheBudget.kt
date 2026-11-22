package dev.alaev.rezkatv.rezka.player

object CacheBudget {

    private const val PREFETCH_PART = 2

    fun allowsPrefetch(fetchedBytes: Long, cacheBytes: Long): Boolean = fetchedBytes < cacheBytes / PREFETCH_PART
}
