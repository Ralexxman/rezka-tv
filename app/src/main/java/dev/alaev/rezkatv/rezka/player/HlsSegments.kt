package dev.alaev.rezkatv.rezka.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.IOException
import java.net.URI

@UnstableApi
object HlsSegments {

    private const val TAG = "RezkaTv"
    private const val MAX_PLAYLIST_DEPTH = 1

    @Volatile private var plan: Pair<String, HlsMediaPlaylist>? = null

    fun from(upstream: DataSource.Factory, url: String, positionMs: Long, height: Int): List<DataSpec> {
        val id = "$height $url"
        val playlist = plan?.takeIf { it.first == id }?.second
            ?: mediaPlaylist(upstream, url, height, 0)?.also { plan = id to it }
        return playlist?.let { mediaSegments(it, positionMs) }.orEmpty()
    }

    private fun mediaPlaylist(upstream: DataSource.Factory, url: String, height: Int, depth: Int): HlsMediaPlaylist? =
        when (val playlist = readPlaylist(upstream, Uri.parse(url))) {
            is HlsMediaPlaylist -> playlist
            is HlsMultivariantPlaylist -> variantPlaylist(upstream, playlist, height, depth)
            else -> null
        }

    private fun variantPlaylist(
        upstream: DataSource.Factory,
        playlist: HlsMultivariantPlaylist,
        height: Int,
        depth: Int,
    ): HlsMediaPlaylist? {
        val fitting = playlist.variants.filter { it.format.height <= height.coerceAtLeast(1) }
        val variant = (fitting.maxByOrNull { it.format.height } ?: playlist.variants.firstOrNull())?.url
        if (variant == null || depth >= MAX_PLAYLIST_DEPTH) return null
        return mediaPlaylist(upstream, URI(playlist.baseUri).resolve(variant.toString()).toString(), height, depth + 1)
    }

    private fun mediaSegments(playlist: HlsMediaPlaylist, positionMs: Long): List<DataSpec> {
        val fromUs = positionMs * 1000
        return playlist.segments
            .filter { it.relativeStartTimeUs + it.durationUs > fromUs }
            .map { DataSpec(Uri.parse(URI(playlist.baseUri).resolve(it.url).toString())) }
    }

    private fun readPlaylist(upstream: DataSource.Factory, uri: Uri): HlsPlaylist? {
        val source = upstream.createDataSource()
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
