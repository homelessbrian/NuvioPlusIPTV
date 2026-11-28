package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.sports.SportsFollows
import com.nuvio.tv.livetv.sports.SportsListingSource
import com.nuvio.tv.livetv.sync.PlaybackSettingsBackup
import com.nuvio.tv.livetv.sync.SyncPart
import com.nuvio.tv.livetv.sync.SyncParts
import com.nuvio.vpn.android.VpnBackup
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class SportsListingBackupTest {
    private lateinit var directory: File
    private lateinit var preferences: LiveTvPreferences
    private val activeProfile = MutableStateFlow(1)
    private val leagueId = "guide/motogp"

    @Before fun setUp() {
        directory = Files.createTempDirectory("sports-listing-backup").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns directory
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns this@SportsListingBackupTest.activeProfile
            every { profiles } returns MutableStateFlow(listOf(
                UserProfile(1, "Primary", "#1E88E5"), UserProfile(2, "Second", "#1E88E5")
            ))
        }
        val playbackBackup = mockk<PlaybackSettingsBackup>(relaxed = true) {
            every { exportDeviceSettings() } answers { JSONObject() }
        }
        preferences = LiveTvPreferences(
            context, profileManager, mockk(relaxed = true), playbackBackup,
            sharedSettings = mockk(relaxed = true), preferenceSettings = mockk(relaxed = true)
        )
        mockkStatic(VpnBackup::class)
        every { VpnBackup.prepareExport(any<Context>(), null) } returns "{\"version\":1,\"profiles\":[]}"
        every { VpnBackup.restoreSetup(any<Context>(), any<String>()) } just Runs
    }

    @After fun tearDown() {
        unmockkStatic(VpnBackup::class)
        directory.deleteRecursively()
    }

    private suspend fun choose(profile: Int, source: SportsListingSource) {
        activeProfile.value = profile
        preferences.editSportsMine { SportsFollows(pins = listOf(leagueId)).withListingSource(leagueId, source).toJson() }
    }

    private suspend fun follows(profile: Int): SportsFollows {
        activeProfile.value = profile
        return SportsFollows.fromJson(preferences.sportsMineJson.first())
    }

    @Test fun portableSetupRoundtripRetainsDifferentChoicesForEachProfile() = runTest {
        choose(1, SportsListingSource.SCHEDULE)
        choose(2, SportsListingSource.GUIDE)
        assertEquals(SportsListingSource.SCHEDULE, follows(1).listingSource(leagueId))
        assertEquals(SportsListingSource.GUIDE, follows(2).listingSource(leagueId))

        val backup = JSONObject(preferences.exportForSync().toString())
        for ((key, source) in listOf("sports_mine" to SportsListingSource.SCHEDULE, "sports_mine_p2" to SportsListingSource.GUIDE)) {
            assertEquals(SyncPart.SPORTS, SyncParts.partOf(key))
            assertEquals(source, SportsFollows.fromJson(backup.getJSONObject(key).getString("v")).listingSource(leagueId))
        }

        choose(1, SportsListingSource.BOTH)
        choose(2, SportsListingSource.BOTH)
        preferences.importFromSync(backup)
        assertEquals(SportsListingSource.SCHEDULE, follows(1).listingSource(leagueId))
        assertEquals(SportsListingSource.GUIDE, follows(2).listingSource(leagueId))
        assertEquals(listOf(leagueId), follows(2).pins)
    }

    @Test fun copiedChoicesStayIndependentAndMatchedProfilesShareTheirSourceChoice() = runTest {
        choose(1, SportsListingSource.SCHEDULE)
        choose(2, SportsListingSource.GUIDE)
        preferences.copyProfileChoices(1, 2)
        assertEquals(SportsListingSource.SCHEDULE, follows(2).listingSource(leagueId))

        choose(1, SportsListingSource.GUIDE)
        assertEquals(SportsListingSource.SCHEDULE, follows(2).listingSource(leagueId))
        preferences.matchProfiles(1, 2)
        assertEquals(SportsListingSource.GUIDE, follows(2).listingSource(leagueId))
        choose(1, SportsListingSource.SCHEDULE)
        assertEquals(SportsListingSource.SCHEDULE, follows(2).listingSource(leagueId))
    }

    @Test fun olderSetupWithoutTheFieldRestoresBothSourcesWithoutRemovingTheFollow() = runTest {
        choose(1, SportsListingSource.SCHEDULE)
        val backup = JSONObject(preferences.exportForSync().toString())
        val legacy = SportsFollows(pins = listOf(leagueId), words = listOf("Rider One")).toJson()
        assertFalse(JSONObject(legacy).has("listingSources"))
        backup.getJSONObject("sports_mine").put("v", legacy)
        preferences.importFromSync(backup)

        val restored = follows(1)
        assertEquals(SportsListingSource.BOTH, restored.listingSource(leagueId))
        assertEquals(listOf(leagueId), restored.pins)
        assertEquals(listOf("Rider One"), restored.words)
    }
}
