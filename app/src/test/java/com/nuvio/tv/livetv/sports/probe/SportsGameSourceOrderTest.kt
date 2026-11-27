package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.sports.ChannelMatch
import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.sports.SportsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SportsGameSourceOrderTest {
    private fun feed(key: String, source: String, name: String, addon: Boolean = false): ChannelMatch =
        ChannelMatch(LiveChannel(key, if (addon) SportsAddonSource.SOURCE_ID else source, source, name,
            null, null, null, "sport", "Sports", 1, "https://example.test/$key"),
            100, "Guide", null, null, false)

    private val low = feed("main-low", "main", "TNT 4 (720p25)")
    private val high = feed("main-high", "main", "TNT 4 (1080p50)")
    private val backup = feed("backup-channel", "backup", "TNT 4 (2160p50)")
    private val addon = feed("sports-stream-match", "Sports Stream", "TNT 4 (480p25)", addon = true)
    private val another = feed("other-addon-match", "Other add-on", "TNT 4 (1080p50)", addon = true)
    private val chosen = listOf(BackupOrder.addonKey("Sports Stream"), "main", "backup")
    private val groups = SportsMatcher.groupCopies(listOf(backup, low, another, high, addon))

    @Test fun sourcePriorityPrecedesHealthAndQualityAndEverySelectedCopyRemainsTheOriginal() {
        val checks = listOf(low, backup, addon, high).associate {
            it.channel.key to SportsProbeResult(SportsProbeStatus.WORKING)
        }
        fun ranked(checksByKey: Map<String, SportsProbeResult>) = sportsGameChannelRows(
            groups, checksByKey, automatic = true, bestChannelFirst = true, sourceOrder = chosen).map { it.single() }
        val rows = ranked(checks)
        assertEquals(listOf(addon, high, low, backup, another), rows)
        assertSame(high, rows[1])
        assertEquals(listOf(addon, low, high, backup, another), ranked(checks +
            (high.channel.key to SportsProbeResult(SportsProbeStatus.UNAVAILABLE))))
    }

    @Test fun sourceOrderExposesEveryCopyEvenWithBestFirstOffAndDefaultStillFolds() {
        val rows = sportsGameChannelRows(groups, emptyMap(), automatic = false, bestChannelFirst = false, sourceOrder = chosen)
        assertEquals(listOf(addon, low, high, backup, another), rows.map { it.single() })
        assertEquals(rows.size, rows.map { it.first().channel.key }.distinct().size)
        assertSame(groups, sportsGameChannelRows(groups, emptyMap(), false, false, emptyList()))
    }

    @Test fun appliedDefaultOrderIsExplicitNewSourcesAreAppendedAndResetOnlyClearsSourcePriority() {
        val initial = LiveTvSettings(backupGroupBy = BackupOrder.QUALITY, backupQualityOrder = "HD,FHD")
        val ids = listOf("main", "backup")
        val addons = listOf("Sports Stream")
        assertEquals(emptyList<String>(), BackupOrder.gameSourceOrder(initial, ids, addons))
        val defaultOrder = BackupOrder.sources(initial, ids, addons)
        val applied = BackupOrder.withGameSourceOrder(initial, defaultOrder)
        assertEquals(BackupOrder.SOURCE, applied.backupGroupBy)
        assertEquals(defaultOrder, BackupOrder.gameSourceOrder(applied, ids, addons))
        val custom = BackupOrder.withGameSourceOrder(applied, chosen)
        assertEquals(chosen + listOf("new", BackupOrder.addonKey("Other add-on")),
            BackupOrder.gameSourceOrder(custom, ids + "new", addons + "Other add-on"))
        assertEquals(emptyList<String>(), BackupOrder.gameSourceOrder(custom.copy(backupGroupBy = BackupOrder.NONE), ids, addons))
        assertEquals(custom.copy(backupSourceOrder = ""), BackupOrder.withGameSourceOrder(custom, emptyList()))
    }
}
