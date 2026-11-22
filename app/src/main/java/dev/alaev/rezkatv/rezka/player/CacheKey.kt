package dev.alaev.rezkatv.rezka.player

object CacheKey {
    fun of(url: String): String {
        val rest = url.substringAfter("://")
        val slash = rest.indexOf('/')
        return if (slash < 0) url else rest.substring(slash)
    }
}
