package com.nuvio.tv.livetv.sports

/** Quality advertised in a playlist. A broad label has a nominal height for sorting only. */
internal data class SportsReportedQuality(val height: Int, val frameRate: Float, val label: String)

private const val HEIGHT = "(4320|2160|1440|1080|720|576|575|540|480|360|240)"
private const val RATE = "([1-9][0-9]{0,2}(?:[.][0-9]{1,3})?)"
private fun qualityToken(value: String) = Regex("(?<![\\p{L}\\p{N}])(?:$value)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

private val resolutionTag = qualityToken("(?:[0-9]{3,4}[x×])?$HEIGHT(?:([pi])(?:\\s*$RATE(\\s*fps)?)?)?")
private val rateTag = qualityToken("$RATE\\s*fps")
private val classTag = qualityToken("8k|4k|uhd|ultra\\s+hd|qhd|fhd|full\\s+hd|hd|sd")

private fun MatchResult.rate(index: Int) =
    groupValues[index].toFloatOrNull()?.takeIf { it in 1f..240f } ?: 0f

private fun rateLabel(rate: Float) = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()

private fun broadQuality(token: String) = when (token.takeWhile { !it.isWhitespace() }.lowercase()) {
    "8k" -> SportsReportedQuality(4320, 0f, "8K")
    "4k", "uhd", "ultra" -> SportsReportedQuality(2160, 0f, "4K")
    "qhd" -> SportsReportedQuality(1440, 0f, "QHD")
    "fhd", "full" -> SportsReportedQuality(1080, 0f, "FHD")
    "hd" -> SportsReportedQuality(720, 0f, "HD")
    else -> SportsReportedQuality(480, 0f, "SD")
}

internal fun reportedSportsQuality(name: String): SportsReportedQuality? {
    val resolution = resolutionTag.find(name)
    if (resolution != null) {
        val height = resolution.groupValues[1].toInt()
        val scan = resolution.groupValues[2].lowercase()
        val attachedRate = resolution.rate(3)
        val rate = attachedRate.takeIf { it > 0f } ?: rateTag.find(name)?.rate(1) ?: 0f
        // In broadcast notation 1080i50 is 50 fields, or 25 frames, per second.
        // An explicit fps unit, e.g. "1080i 25fps", already counts complete frames.
        val fieldRate = scan == "i" && attachedRate > 0f && resolution.groupValues[4].isEmpty()
        val label = when {
            rate == 0f -> "$height$scan"
            scan.isEmpty() || scan == "i" && !fieldRate -> "$height$scan ${rateLabel(rate)}fps"
            else -> "$height$scan${rateLabel(rate)}"
        }
        return SportsReportedQuality(height, if (fieldRate) rate / 2 else rate, label)
    }
    val broad = classTag.findAll(name).map { broadQuality(it.value) }.maxByOrNull { it.height } ?: return null
    val rate = rateTag.find(name)?.rate(1) ?: return broad
    return if (rate > 0f) broad.copy(frameRate = rate, label = "${broad.label} ${rateLabel(rate)}fps") else broad
}

internal fun withoutSportsQualityTags(name: String): String =
    classTag.replace(rateTag.replace(resolutionTag.replace(name, " "), " "), " ")

/** Previous playback stores only a broad tier. Use finer provider detail when the tier agrees. */
internal fun sportsMatchQuality(name: String, saved: String?): SportsReportedQuality? {
    val reported = reportedSportsQuality(name)
    if (saved == null || saved == reported?.label) return reported
    if (reported != null) {
        val tier = when {
            reported.height >= 1800 -> "4K"
            reported.height >= 1000 -> "FHD"
            reported.height >= 700 -> "HD"
            else -> "SD"
        }
        if (tier == saved) return reported
    }
    return reportedSportsQuality(saved) ?: reported
}
