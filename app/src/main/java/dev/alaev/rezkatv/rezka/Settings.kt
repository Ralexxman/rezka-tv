package dev.alaev.rezkatv.rezka

import android.content.Context
import androidx.preference.PreferenceManager

class Settings(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    val quality: String get() = prefs.getString(KEY_QUALITY, AUTO) ?: AUTO
    val autoplayNext: Boolean get() = prefs.getBoolean(KEY_AUTOPLAY, true)
    val seekStepMs: Long get() = seekStepMs(prefs.getString(KEY_SEEK_STEP, null))
    val liteMode: Boolean get() = prefs.getBoolean(KEY_LITE, false)
    val cacheGb: Int get() = (prefs.getString(KEY_CACHE_GB, null)?.toIntOrNull() ?: DEFAULT_CACHE_GB).coerceIn(1, 8)
    val baseUrl: String
        get() {
            val saved = prefs.getString(KEY_MIRROR, null)
            return if (saved == null || saved == RETIRED_MIRROR) RezkaClient.DEFAULT_BASE else saved
        }

    fun setQuality(label: String) = prefs.edit().putString(KEY_QUALITY, label).apply()
    fun setBaseUrl(url: String) = prefs.edit().putString(KEY_MIRROR, url).apply()

    companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_AUTOPLAY = "autoplay_next"
        const val KEY_SEEK_STEP = "seek_step"
        const val KEY_LITE = "lite_mode"
        const val KEY_CACHE_GB = "cache_gb"
        const val KEY_MIRROR = "mirror"
        const val AUTO = "auto"
        const val BEST = "best"
        private const val RETIRED_MIRROR = "https://rezka.fi/"
        private const val DEFAULT_SEEK_STEP_S = 10L
        private const val DEFAULT_CACHE_GB = 4

        fun seekStepMs(value: String?): Long = (value?.toLongOrNull()?.takeIf { it in 1..120 } ?: DEFAULT_SEEK_STEP_S) * 1000
    }
}

object QualityPicker {

    fun pick(available: List<Quality>, preferred: String, screenHeight: Int): Quality? {
        if (available.isEmpty()) return null
        available.find { it.label == preferred }?.let { return it }
        val byHeight = compareBy<Quality>({ it.height }, { it.ultra })
        if (preferred == Settings.BEST) return available.maxWith(byHeight)
        val limit = if (preferred == Settings.AUTO) screenHeight else Quality(preferred, emptyList()).height
        val wantUltra = preferred != Settings.AUTO && Quality(preferred, emptyList()).ultra
        return available
            .filter { it.height <= limit && (wantUltra || !it.ultra) }
            .maxWithOrNull(byHeight)
            ?: available.minBy { it.height }
    }
}
