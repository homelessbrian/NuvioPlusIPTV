package com.nuvio.tv.livetv.multiview

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.DrmInfo
import com.nuvio.tv.livetv.player.LiveCaptions

/**
 * Immutable source configuration shared with single-channel Live TV. Each request explicitly
 * supplies its own DRM/format configuration; no singleton channel or caption detector is read.
 */
@OptIn(UnstableApi::class)
internal class LiveTvMediaSourceBuilder(
    private val drm: DrmInfo? = null,
    private val forcedMime: String? = null,
    private val bufferSize: String = "normal",
    private val observeCaptions: Boolean = true,
    private val strictDrm: Boolean = false,
    private val requireHttpSources: Boolean = false,
) {
    private fun captionExtractor(factory: DefaultHlsExtractorFactory): HlsExtractorFactory =
        if (observeCaptions) LiveCaptions.Hls(factory) else factory

    fun build(url: String, headers: Map<String, String>, isLive: Boolean): androidx.media3.exoplayer.source.MediaSource {
        // RTSP sources open their own sockets and would bypass the protected HTTP factory.
        require(!requireHttpSources || MultiviewSourcePolicy.allows(url)) {
            "This stream format is unavailable in Multiview"
        }
        // Protected (DRM) streams, e.g. MPEG-DASH with a ClearKey or Widevine license.
        drm?.let { protection ->
            require(!strictDrm || MultiviewSourcePolicy.supportsDrmScheme(protection.scheme)) {
                "This DRM scheme is unavailable in Multiview"
            }
            buildDrmMediaSource(url, headers, isLive, protection)?.let { return it }
            check(!strictDrm) { "Protected stream could not be prepared" }
        }
        val http = com.nuvio.tv.core.vpn.protectHttpFactory(DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000), headers, headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
        val item = buildMediaItem(url, isLive)
        // Closed captions: TV channels usually carry them inside the video (CEA-608/708) without
        // announcing them, so the player never offered them and Subtitles only showed "Off".
        // Look for them anyway, the way TV apps do.
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(http)
                .setExtractorFactory(
                    captionExtractor(
                        androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(
                            androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
                            /* exposeCea608WhenMissingDeclarations = */ true
                        )
                    )
                )
                .createMediaSource(item)
        }
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS
            )
            // CC1–CC4 and the digital (708) captions; the menu only offers those with text.
            .setTsSubtitleFormats(LiveCaptions.formats)
        return DefaultMediaSourceFactory(http, if (observeCaptions) LiveCaptions.Extractors(extractors) else extractors).createMediaSource(item)
    }

    /**
     * DRM playback, as TiviMate and Kodi do it from "#KODIPROP" playlist lines:
     *  - Widevine / PlayReady: the license server URL (with any headers the playlist gives).
     *  - ClearKey: keys given in the playlist ("kid:key" pairs or a JSON key set), or a ClearKey
     *    license URL. Widevine needs a device that supports it (most Android TV devices do).
     */
    private fun buildDrmMediaSource(
        url: String,
        headers: Map<String, String>,
        isLive: Boolean,
        drm: com.nuvio.tv.livetv.model.DrmInfo
    ): androidx.media3.exoplayer.source.MediaSource? = runCatching {
        val http = com.nuvio.tv.core.vpn.protectHttpFactory(DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000), headers, headers["User-Agent"] ?: LiveTvRepository.DEFAULT_UA)
        val itemBuilder = buildMediaItem(url, isLive).buildUpon()
        if (drm.manifestType == "mpd" || url.lowercase().contains(".mpd")) itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        if (drm.manifestType == "hls") itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        // Single-channel defaults remain unchanged; strict multiview accepts supported aliases only.
        val scheme = if (strictDrm) drm.scheme.trim().lowercase() else drm.scheme
        val uuid = when (scheme) {
            "widevine" -> androidx.media3.common.C.WIDEVINE_UUID
            "playready" -> androidx.media3.common.C.PLAYREADY_UUID
            else -> androidx.media3.common.C.CLEARKEY_UUID
        }
        val factory = DefaultMediaSourceFactory(http)
        val license = drm.license.trim()
        if (scheme == "clearkey" && !license.startsWith("http", ignoreCase = true)) {
            // Keys in the playlist: answer license requests locally with them.
            val keySet = clearKeyJson(license) ?: return@runCatching null
            factory.setDrmSessionManagerProvider {
                androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(androidx.media3.common.C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(androidx.media3.exoplayer.drm.LocalMediaDrmCallback(keySet.toByteArray(Charsets.UTF_8)))
            }
            itemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(androidx.media3.common.C.CLEARKEY_UUID).build())
        } else {
            require(!requireHttpSources || MultiviewSourcePolicy.allows(license)) {
                "This DRM license format is unavailable in Multiview"
            }
            // License requests go out with the same User-Agent / Referer as the stream (like
            // TiviMate and Kodi), plus any license headers from the playlist. Without them many
            // license servers refuse the request.
            val licenseHeaders = MultiviewSourcePolicy.licenseHeaders(
                headers, drm.licenseHeaders, caseInsensitiveOverrides = strictDrm,
            )
            // Factory defaults are merged into license requests by Media3. A separate strict
            // factory prevents the stream's Authorization/Cookie from leaking to a license host.
            val licenseHttp = if (strictDrm) {
                val userAgent = licenseHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                    ?: LiveTvRepository.DEFAULT_UA
                com.nuvio.tv.core.vpn.protectHttpFactory(
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(userAgent)
                        .setDefaultRequestProperties(licenseHeaders.filterKeys { !it.equals("User-Agent", true) })
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000),
                    licenseHeaders, userAgent,
                )
            } else http
            factory.setDrmSessionManagerProvider(
                androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(licenseHttp) }
            )
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(license)
                    .setLicenseRequestHeaders(licenseHeaders)
                    .setMultiSession(true)
                    .build()
            )
        }
        factory.createMediaSource(itemBuilder.build())
    }.getOrNull()

    /**
     * A ClearKey key set (JSON) from what playlists give: "kid:key" pairs (hex or base64,
     * comma-separated), or a JSON key set as is.
     */
    private fun clearKeyJson(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("{")) return t
        fun b64url(v: String): String {
            val s = v.trim()
            val bytes = if (Regex("^[0-9a-fA-F]+$").matches(s) && s.length % 2 == 0) {
                ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else {
                runCatching { android.util.Base64.decode(s.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT) }
                    .getOrElse { return "" }
            }
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        }
        val keys = t.split(',').mapNotNull { pair ->
            val kid = pair.substringBefore(':').trim()
            val key = pair.substringAfter(':', "").trim()
            if (kid.isEmpty() || key.isEmpty()) null
            else org.json.JSONObject().put("kty", "oct").put("kid", b64url(kid)).put("k", b64url(key))
        }
        if (keys.isEmpty()) return null
        return org.json.JSONObject().put("keys", org.json.JSONArray(keys)).put("type", "temporary").toString()
    }

    private fun buildMediaItem(url: String, isLive: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        val lower = url.lowercase().substringBefore('?')
        when {
            forcedMime != null -> builder.setMimeType(forcedMime)
            lower.endsWith(".m3u8") || lower.contains("/hls/") || lower.contains("m3u8") ||
                com.nuvio.tv.livetv.model.PlaylistSource.asksForHls(url) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.endsWith(".ts") -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        if (isLive) {
            // How far behind "live" to play. Playing very close to live (it was 8 seconds) left
            // almost no room for a slow segment, so some providers kept buffering; now it
            // follows the stream's own recommendation, with more room for bigger buffers.
            val offset = when (bufferSize) {
                "none" -> 3_000L
                "small" -> 10_000L
                "large" -> 30_000L
                "xlarge" -> 45_000L
                else -> null
            }
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .apply { if (offset != null) setTargetOffsetMs(offset) }
                    // No speeding up / slowing down to chase the live edge (can cause stutter).
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
            )
        }
        return builder.build()
    }

}
