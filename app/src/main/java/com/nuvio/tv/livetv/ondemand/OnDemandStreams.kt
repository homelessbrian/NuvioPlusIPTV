package com.nuvio.tv.livetv.ondemand

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OnDemandEntryPoint {
    fun onDemandRepository(): OnDemandRepository
    fun tmdbService(): TmdbService
    fun liveTvPreferences(): com.nuvio.tv.livetv.data.LiveTvPreferences
}

fun onDemandRepository(context: Context): OnDemandRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, OnDemandEntryPoint::class.java).onDemandRepository()

/**
 * Adds a "Watch On Demand" group to the streams Nuvio finds for a movie or episode, when one of
 * your IPTV providers has it. It shows up next to your addon and debrid streams.
 */
object OnDemandStreams {
    /** The label for the provider's copies in Nuvio's stream list. */
    const val GROUP_NAME = "📡 On Demand"

    /** Binge group for every provider copy: the next episode is picked from your provider too. */
    const val BINGE_GROUP = "nuvio-iptv-on-demand"

    /**
     * True while provider movies/series are imported and offered in Nuvio's stream list. Nuvio
     * greys out Play ("Playback unavailable") when none of your addons can stream a title (for
     * example shows without an IMDb id); with On Demand on, your provider may still have it.
     */
    @Volatile
    var offeredInStreams: Boolean = false

    /** Auto-play may pick your provider's copy (set on, and its tab is first). */
    @Volatile
    var autoPlayAllowed: Boolean = false

    /** Where the On Demand tab goes ("FIRST", "AFTER_FIRST" or "LAST"), kept up to date from settings. */
    @Volatile
    var tabPosition: String = "LAST"

    /** Puts the On Demand tab in its place, after Nuvio has ordered the addon tabs. */
    fun placeTab(ordered: List<AddonStreams>, mine: AddonStreams): List<AddonStreams> = place(ordered, mine, tabPosition)

    /** Puts the On Demand group where it's set to go: first, after the first addon, or last. */
    private fun place(groups: List<AddonStreams>, mine: AddonStreams, position: String): List<AddonStreams> = when (position) {
        "FIRST" -> listOf(mine) + groups
        "AFTER_FIRST" -> groups.take(1) + mine + groups.drop(1)
        else -> groups + mine
    }

    fun withOnDemand(
        context: Context,
        source: Flow<NetworkResult<List<AddonStreams>>>,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        title: String,
        year: String?
    ): Flow<NetworkResult<List<AddonStreams>>> {
        val entry = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, OnDemandEntryPoint::class.java)
        }.getOrNull() ?: return source
        // Your provider's copies, and where they go in the list (their tab, left to right): set
        // in Live TV settings, last by default.
        val onDemand = flow {
            emit(emptyList<Stream>() to "LAST")
            val position = runCatching { entry.liveTvPreferences().settings.first().onDemandStreamsPosition }.getOrDefault("LAST")
            val found = runCatching { lookup(entry, type, videoId, season, episode, title, year) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) emit(found to position)
        }
        return combine(source, onDemand) { result, (extra, position) ->
            when {
                extra.isEmpty() -> result
                result is NetworkResult.Success -> NetworkResult.Success(place(result.data, AddonStreams(GROUP_NAME, null, extra), position))
                // No addon found anything (or they failed): your provider's copy is still offered,
                // instead of "playback unavailable".
                result is NetworkResult.Error -> NetworkResult.Success(listOf(AddonStreams(GROUP_NAME, null, extra)))
                else -> result
            }
        }
    }

    private suspend fun lookup(
        entry: OnDemandEntryPoint,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        title: String,
        year: String?
    ): List<Stream> {
        val show = runCatching { entry.liveTvPreferences().settings.first().let { it.onDemandInStreams && it.onDemandInSidebar } }.getOrDefault(true)
        if (!show) return emptyList()
        val repo = entry.onDemandRepository()
        repo.start()
        if (!repo.hasContent.value) return emptyList()
        val base = videoId.substringBefore(':')
        val tmdbId = when {
            base.startsWith("tmdb", ignoreCase = true) -> videoId.substringAfter("tmdb:").substringBefore(':').takeIf { it.all(Char::isDigit) }
            base.startsWith("tt") -> runCatching {
                entry.tmdbService().imdbToTmdb(base, if (type.equals("movie", true)) "movie" else "tv")?.toString()
            }.getOrNull()
            else -> null
        }
        val sources = repo.sourcesFor(type, tmdbId, title, year?.take(4)?.toIntOrNull(), season, episode)
        return sources.map { s ->
            val t = s.tech
            val quality = t?.quality
            Stream(
                name = listOfNotNull(GROUP_NAME, quality).joinToString("\n"),
                title = s.label,
                description = describe(s),
                url = s.url,
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = StreamBehaviorHints(
                    notWebReady = if (s.headers.isEmpty()) null else true,
                    // The same for every episode from your provider: Nuvio's "next episode from the
                    // same source" then carries on with your provider's copy automatically.
                    bingeGroup = BINGE_GROUP,
                    countryWhitelist = null,
                    proxyHeaders = if (s.headers.isEmpty()) null else ProxyHeaders(request = s.headers, response = null),
                    videoSize = t?.estimatedBytes,
                    // A release-style name, so Nuvio's own badge rules (4K, HEVC, HDR, 5.1…)
                    // recognise the stream the same way they do addon streams.
                    filename = releaseName(s)
                ),
                addonName = GROUP_NAME,
                addonLogo = null,
                quality = quality,
                qualityValue = t?.height ?: -1
            )
        }
    }

    /**
     * The card text, laid out like popular stream addons:
     *   🎬 Obsession (2025)
     *   💾 ~5.4 GB | 🗣 English | 📡 My provider
     *   🎞 4K · HEVC · Dolby Vision · 23.98 fps  🔊 E-AC3 5.1
     */
    private fun describe(s: OnDemandRepository.OnDemandSource): String {
        val t = s.tech
        val head = "🎬 " + s.title + (s.year?.let { " ($it)" } ?: "") + (s.episodeTag?.let { " · $it" } ?: "")
        val line2 = listOfNotNull(
            t?.estimatedBytes?.let { "💾 ~" + formatSize(it) },
            t?.language?.let { "🗣 $it" },
            "📡 ${s.providerName}"
        ).joinToString(" | ")
        val video = listOfNotNull(
            t?.quality,
            t?.videoCodec?.let { codecName(it) },
            t?.hdr,
            t?.fps?.let { "%.2f fps".format(it).replace(".00", "") }
        ).joinToString(" · ")
        val audio = listOfNotNull(t?.audioCodec?.let { codecName(it) }, t?.channels?.let { channelsName(it) }).joinToString(" ")
        val line3 = listOfNotNull(
            video.takeIf { it.isNotBlank() }?.let { "🎞 $it" },
            audio.takeIf { it.isNotBlank() }?.let { "🔊 $it" },
            t?.durationSecs?.let { "⏱ ${it / 3600}h ${(it % 3600) / 60}m" }
        ).joinToString("  ")
        return listOf(head, line2, line3).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** e.g. "Obsession.2025.2160p.HEVC.DV.EAC3.5.1.mkv" */
    private fun releaseName(s: OnDemandRepository.OnDemandSource): String {
        val t = s.tech
        val res = when (t?.quality) { "4K" -> "2160p"; "1080p" -> "1080p"; "720p" -> "720p"; "SD" -> "480p"; else -> null }
        val hdr = when (t?.hdr) { "Dolby Vision" -> "DV"; "HDR10" -> "HDR10"; "HLG" -> "HLG"; else -> null }
        return listOfNotNull(
            s.title.replace(Regex("""[^\p{L}\p{N}]+"""), ".").trim('.'),
            s.year?.toString(),
            s.episodeTag,
            res,
            t?.videoCodec?.let { codecName(it) },
            hdr,
            t?.audioCodec?.let { codecName(it) },
            t?.channels?.let { channelsName(it) }
        ).joinToString(".") + "." + (s.ext ?: "mp4")
    }

    private fun codecName(c: String): String = when (c.lowercase()) {
        "hevc", "h265" -> "HEVC"
        "h264", "avc" -> "H.264"
        "av1" -> "AV1"
        "vp9" -> "VP9"
        "mpeg2video" -> "MPEG-2"
        "eac3" -> "E-AC3"
        "ac3" -> "AC3"
        "truehd" -> "TrueHD"
        "dts" -> "DTS"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        else -> c.uppercase()
    }

    private fun channelsName(n: Int): String = when (n) {
        1 -> "Mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "${n}ch"
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) "%.1f GB".format(gb) else "%d MB".format(bytes / 1_000_000)
    }
}
