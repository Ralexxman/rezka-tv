package dev.alaev.rezkatv.rezka.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import dev.alaev.rezkatv.rezka.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URI

@UnstableApi
object VideoCache {

    private const val TAG = "RezkaTv"
    private const val FOLDER = "video"
    private const val GB = 1024L * 1024 * 1024
    private const val BUDGET_PART = 2
    private const val READ_BUFFER = 64 * 1024
    private const val MAX_PLAYLIST_DEPTH = 1
    private const val HLS = ".m3u8"

    private val lock = Any()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cache: SimpleCache? = null
    private var sizeBytes = 0L
    private var upstream: DataSource.Factory? = null
    private var job: Job? = null
    private var jobUrl: String? = null

    @Volatile private var fetched = 0L

    val prefetchedBytes: Long? get() = fetched.takeIf { job?.isActive == true }

    fun install(context: Context, upstreamFactory: DataSource.Factory): DataSource.Factory {
        val app = context.applicationContext
        upstream = upstreamFactory
        val store = synchronized(lock) {
            val ready = cache
            if (ready != null && sizeBytes != Settings(app).cacheGb * GB) {
                cancel()
                ready.release()
                cache = null
            }
            cache ?: create(app)
        }
        return CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun clear() {
        cancel()
        val store = synchronized(lock) { cache } ?: return
        io.launch { store.keys.forEach { store.removeResource(it) } }
    }

    fun prefetch(url: String, positionMs: Long, height: Int) {
        val running = job?.isActive == true && jobUrl == url
        val store = cache
        val factory = upstream
        if (running || store == null || factory == null) return
        cancel()
        val source = CacheDataSource.Factory().setCache(store).setUpstreamDataSourceFactory(factory).createDataSource()
        jobUrl = url
        fetched = 0L
        job = io.launch { fill(source, store, url, positionMs, height) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        jobUrl = null
    }

    private fun create(app: Context): SimpleCache {
        val size = Settings(app).cacheGb * GB
        val dir = File(app.cacheDir, FOLDER)
        return SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(size),
            StandaloneDatabaseProvider(app),
        ).also {
            cache = it
            sizeBytes = size
        }
    }

    private fun CoroutineScope.fill(
        source: DataSource,
        store: SimpleCache,
        url: String,
        positionMs: Long,
        height: Int,
    ) {
        val buffer = ByteArray(READ_BUFFER)
        val start = store.cacheSpace
        val specs = if (url.endsWith(HLS)) {
            segments(url, positionMs, height)
        } else {
            val spec = DataSpec(Uri.parse(url))
            val key = CacheKeyFactory.DEFAULT.buildCacheKey(spec)
            val cached = store.getCachedBytes(key, 0, Long.MAX_VALUE).coerceAtLeast(0)
            listOf(spec.buildUpon().setPosition(cached).build())
        }
        try {
            for (spec in specs) {
                if (!isActive || store.cacheSpace - start >= sizeBytes / BUDGET_PART) break
                drain(source, spec, buffer, store, start)
            }
        } catch (e: IOException) {
            Log.i(TAG, "prefetch stopped: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.i(TAG, "prefetch stopped: ${e.message}")
        } finally {
            DataSourceUtil.closeQuietly(source)
        }
    }

    private fun CoroutineScope.drain(
        source: DataSource,
        spec: DataSpec,
        buffer: ByteArray,
        store: SimpleCache,
        start: Long,
    ) {
        source.open(spec)
        try {
            while (isActive && store.cacheSpace - start < sizeBytes / BUDGET_PART) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                fetched += read
            }
        } finally {
            DataSourceUtil.closeQuietly(source)
        }
    }

    private fun segments(url: String, positionMs: Long, height: Int, depth: Int = 0): List<DataSpec> {
        val uri = Uri.parse(url)
        val playlist = readPlaylist(uri) ?: return emptyList()
        return when (playlist) {
            is HlsMultivariantPlaylist -> variantSegments(playlist, positionMs, height, depth)
            is HlsMediaPlaylist -> mediaSegments(playlist, positionMs)
            else -> emptyList()
        }
    }

    private fun variantSegments(
        playlist: HlsMultivariantPlaylist,
        positionMs: Long,
        height: Int,
        depth: Int,
    ): List<DataSpec> {
        val fitting = playlist.variants.filter { it.format.height <= height.coerceAtLeast(1) }
        val variant = (fitting.maxByOrNull { it.format.height } ?: playlist.variants.firstOrNull())?.url
        val deeper = depth < MAX_PLAYLIST_DEPTH
        if (variant == null || !deeper) return emptyList()
        return segments(URI(playlist.baseUri).resolve(variant.toString()).toString(), positionMs, height, depth + 1)
    }

    private fun mediaSegments(playlist: HlsMediaPlaylist, positionMs: Long): List<DataSpec> {
        val fromUs = positionMs * 1000
        return playlist.segments
            .filter { it.relativeStartTimeUs + it.durationUs > fromUs }
            .map { DataSpec(Uri.parse(URI(playlist.baseUri).resolve(it.url).toString())) }
    }

    private fun readPlaylist(uri: Uri): HlsPlaylist? {
        val source = upstream?.createDataSource() ?: return null
        val stream = DataSourceInputStream(source, DataSpec(uri))
        return try {
            HlsPlaylistParser().parse(uri, stream)
        } catch (e: IOException) {
            Log.w(TAG, "playlist ${uri.lastPathSegment}: ${e.message}")
            null
        } finally {
            DataSourceUtil.closeQuietly(source)
        }
    }
}
