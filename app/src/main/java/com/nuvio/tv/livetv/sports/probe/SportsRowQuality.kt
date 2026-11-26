package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.sports.ChannelMatch
import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.sports.reportedSportsQuality
import com.nuvio.tv.livetv.sports.sportsMatchQuality
import kotlin.math.roundToInt

/** What we can tell the viewer about this particular copy, including where each value came from. */
internal data class SportsRowQuality(
    val height: Int,
    val frameRate: Float,
    val label: String,
    val source: String,
)

internal fun sportsRowQuality(match: ChannelMatch, probe: SportsProbeResult?): SportsRowQuality? {
    val advertised = reportedSportsQuality(match.channel.name)
    val fallback = sportsMatchQuality(match.channel.name, match.quality)
    val height = probe?.height ?: 0
    val measuredRate = probe?.frameRate?.takeIf { it in 1f..240f }
    if (height > 0) {
        // Sample dimensions alone cannot establish whether the stream is progressive or interlaced.
        val size = if (probe != null && probe.width > 0) "${probe.width}×$height" else "$height lines"
        val matchingReport = advertised?.takeIf { it.height == height && it.frameRate > 0 }
        val label = if (measuredRate == null) size else "$size · ${formatRate(measuredRate)} fps"
        val source = if (measuredRate == null && matchingReport != null)
            "Measured · ${matchingReport.label} reported" else "Measured"
        return SportsRowQuality(height, measuredRate ?: matchingReport?.frameRate ?: 0f, label, source)
    }
    if (fallback == null) {
        return measuredRate?.let { SportsRowQuality(0, it, "${formatRate(it)} fps", "Measured") }
    }
    val source = if (advertised == fallback || match.channel.sourceId == SportsAddonSource.SOURCE_ID) "Reported" else "Saved"
    return if (measuredRate == null) SportsRowQuality(fallback.height, fallback.frameRate, fallback.label, source)
    else SportsRowQuality(fallback.height, measuredRate, "${formatRate(measuredRate)} fps", "Measured · ${fallback.label} ${source.lowercase()}")
}

private fun formatRate(value: Float): String {
    val rounded = (value * 100f).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}
