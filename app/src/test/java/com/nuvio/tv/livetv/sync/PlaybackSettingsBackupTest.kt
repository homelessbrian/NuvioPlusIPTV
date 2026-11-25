package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.usenet.UsenetConfiguration
import com.nuvio.tv.core.usenet.UsenetSettings
import com.nuvio.tv.livetv.data.DnsChoice
import com.nuvio.tv.livetv.data.ForkDns
import com.nuvio.tv.livetv.record.NetworkFolderStore
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.ui.screens.player.audiosync.AudioSamplingMode
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncFallback
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncSettings
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackSettingsBackupTest {
    private val context = mockk<Context>()
    private val autoPreferences = mockk<SharedPreferences>(relaxed = true)
    private val audioPreferences = mockk<SharedPreferences>(relaxed = true)
    private val usenet = mockk<UsenetSettings>()
    private val configuration = MutableStateFlow(UsenetConfiguration())
    private val enabled = MutableStateFlow(true)
    private val aggressive = MutableStateFlow(true)
    private val tolerance = MutableStateFlow(300)
    private val audioEnabled = MutableStateFlow(false)
    private val sampling = MutableStateFlow(AudioSamplingMode.LOW)
    private var thumbnails = SeekThumbSettings.Values()
    private var dns = DnsChoice.SYSTEM
    private var networkBuffer = 1024
    private lateinit var backup: PlaybackSettingsBackup

    @Before fun setUp() {
        every { context.getSharedPreferences("nuvio_tv_autosync", Context.MODE_PRIVATE) } returns autoPreferences
        every { context.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE) } returns audioPreferences
        every { usenet.settings } returns configuration
        every { usenet.update(any()) } answers { configuration.value = firstArg() }
        mockkObject(AutoSyncPreferences, AudioSyncSettings, AudioSyncFallback.Companion, SeekThumbSettings, ForkDns, NetworkFolderStore)
        every { AutoSyncPreferences.ensureLoaded(context) } just Runs
        every { AudioSyncFallback.initialize(context) } just Runs
        every { AutoSyncPreferences.enabled } returns enabled
        every { AutoSyncPreferences.aggressiveMode } returns aggressive
        every { AutoSyncPreferences.syncToleranceMs } returns tolerance
        every { AutoSyncPreferences.setEnabled(context, any()) } answers { enabled.value = secondArg() }
        every { AutoSyncPreferences.setAggressiveMode(context, any()) } answers { aggressive.value = secondArg() }
        every { AutoSyncPreferences.setSyncToleranceMs(context, any()) } answers { tolerance.value = secondArg() }
        every { AudioSyncSettings.fallbackEnabled } returns audioEnabled
        every { AudioSyncSettings.samplingMode } returns sampling
        every { AudioSyncSettings.setFallbackEnabled(any()) } answers { audioEnabled.value = firstArg() }
        every { AudioSyncSettings.setSamplingMode(any()) } answers { sampling.value = firstArg() }
        every { SeekThumbSettings.load(context) } answers { thumbnails }
        every { SeekThumbSettings.update(context, any()) } answers {
            thumbnails = secondArg<(SeekThumbSettings.Values) -> SeekThumbSettings.Values>()(thumbnails)
        }
        every { ForkDns.init(context) } just Runs
        every { ForkDns.choice } answers { dns }
        every { ForkDns.set(context, any()) } answers { dns = secondArg() }
        every { NetworkFolderStore.bufferMb(context) } answers { networkBuffer }
        every { NetworkFolderStore.setBufferMb(context, any()) } answers { networkBuffer = secondArg() }
        backup = PlaybackSettingsBackup(context, usenet)
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `subtitle settings roundtrip through their active owners`() {
        enabled.value = false
        aggressive.value = false
        tolerance.value = 500
        audioEnabled.value = true
        sampling.value = AudioSamplingMode.HIGH
        val saved = JSONObject().also(backup::exportInto)
        enabled.value = true
        aggressive.value = true
        tolerance.value = 0
        audioEnabled.value = false
        sampling.value = AudioSamplingMode.OFF

        backup.validate(saved)
        backup.restoreFrom(saved)

        assertFalse(enabled.value)
        assertFalse(aggressive.value)
        assertEquals(500, tolerance.value)
        assertTrue(audioEnabled.value)
        assertEquals(AudioSamplingMode.HIGH, sampling.value)
        verify { AudioSyncFallback.initialize(context) }
        val roundtrip = JSONObject().also(backup::exportInto)
        assertEquals(saved.toMap(), roundtrip.toMap())
    }

    @Test fun `malformed subtitle family changes none of the valid preceding settings`() {
        val malformed = JSONObject()
            .put(PlaybackSettingsBackup.ENABLED, entry("b", false))
            .put(PlaybackSettingsBackup.AUDIO_SAMPLING, entry("str", "UNKNOWN"))
        assertRejected { backup.restoreFrom(malformed) }
        assertTrue(enabled.value)
        verify(exactly = 0) { AutoSyncPreferences.setEnabled(any(), any()) }
        verify(exactly = 0) { AudioSyncFallback.initialize(any()) }
        assertRejected { backup.validate(JSONObject().put(PlaybackSettingsBackup.TOLERANCE, entry("i", "300"))) }
        assertRejected { backup.validate(JSONObject().put("subtitle_sync_unknown", entry("b", true))) }
    }

    @Test fun `explicit device backup restores all tuning choices while keeping runtime memory data`() {
        val chosen = UsenetConfiguration(
            profile = "throughput", readAhead = 256, maxConnections = 47, prewarmOnLaunch = true,
            fastMkvStartup = false, fastNzbFetch = false, prefetchResults = true, cacheNzb = false,
            allowPrivateNetwork = true, holeFilling = true, maxMissingArticles = 9, maxConsecutiveMissing = 4,
            fallbackEnabled = true, fallbackMaxAttempts = 12, searchUserAgent = "Search/1", downloadUserAgent = "Download/2"
        )
        configuration.value = chosen
        thumbnails = SeekThumbSettings.Values(true, true, SeekThumbSettings.When.WATCHING, 250, 123L)
        dns = DnsChoice.GOOGLE
        networkBuffer = 500
        val saved = backup.exportDeviceSettings()
        assertFalse(saved.toString().contains("lastLowMemoryAt"))
        assertFalse(saved.toString().contains("low_memory_at"))
        configuration.value = UsenetConfiguration()
        thumbnails = SeekThumbSettings.Values(lastLowMemoryAt = 999L)
        dns = DnsChoice.SYSTEM
        networkBuffer = 1024

        backup.validateDeviceSettings(saved)
        backup.restoreDeviceSettings(saved)

        assertEquals(chosen, configuration.value)
        assertEquals(SeekThumbSettings.Values(true, true, SeekThumbSettings.When.WATCHING, 250, 999L), thumbnails)
        assertEquals(DnsChoice.GOOGLE, dns)
        assertEquals(500, networkBuffer)
    }

    @Test fun `old partial backup preserves omitted fields and ignores future device fields`() {
        val prior = UsenetConfiguration(readAhead = 128, maxConnections = 45, searchUserAgent = "Custom/1")
        configuration.value = prior
        thumbnails = SeekThumbSettings.Values(true, true, SeekThumbSettings.When.RIGHT_AWAY, 500, 77L)
        val saved = JSONObject()
            .put("usenet", JSONObject().put("fastMkvStartup", false).put("futureChoice", 17))
            .put("seekThumbnails", JSONObject().put("storageMb", 100).put("futureChoice", true))
            .put("futureSection", JSONObject().put("enabled", true))
        backup.restoreDeviceSettings(saved)
        assertEquals(prior.copy(fastMkvStartup = false), configuration.value)
        assertEquals(SeekThumbSettings.Values(true, true, SeekThumbSettings.When.RIGHT_AWAY, 100, 77L), thumbnails)

        backup.restoreDeviceSettings(JSONObject())
        backup.restoreFrom(JSONObject())
        assertEquals(prior.copy(fastMkvStartup = false), configuration.value)
        assertTrue(enabled.value)
        assertEquals(DnsChoice.SYSTEM, dns)
    }

    @Test fun `invalid device section rejects every family before owners mutate`() {
        val malformed = JSONObject()
            .put("usenet", JSONObject().put("readAhead", 128))
            .put("seekThumbnails", JSONObject().put("storageMb", 101))
            .put("dns", "GOOGLE")
        assertRejected { backup.restoreDeviceSettings(malformed) }
        verify(exactly = 0) { usenet.update(any()) }
        verify(exactly = 0) { SeekThumbSettings.update(any(), any()) }
        verify(exactly = 0) { ForkDns.set(any(), any()) }
        assertRejected { backup.validateDeviceSettings(JSONObject().put("usenet", JSONObject().put("readAhead", "128"))) }
        assertRejected { backup.validateDeviceSettings(JSONObject().put("usenet", JSONObject().put("maxMissingArticles", 1))) }
        assertRejected { backup.validateDeviceSettings(JSONObject().put("networkRecordingBufferMb", 3)) }
    }

    @Test fun `only exact subtitle preference changes trigger sync and closing detaches listeners`() {
        val autoListener = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        val audioListener = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { autoPreferences.registerOnSharedPreferenceChangeListener(capture(autoListener)) } just Runs
        every { audioPreferences.registerOnSharedPreferenceChangeListener(capture(audioListener)) } just Runs
        var changes = 0
        val observer = backup.observeChanges { changes++ }
        autoListener.captured.onSharedPreferenceChanged(autoPreferences, "runtime_session")
        audioListener.captured.onSharedPreferenceChanged(audioPreferences, "model_downloaded")
        assertEquals(0, changes)
        autoListener.captured.onSharedPreferenceChanged(autoPreferences, "automatic_subtitle_sync_tolerance_ms")
        audioListener.captured.onSharedPreferenceChanged(audioPreferences, "audio_sync_sampling_mode")
        assertEquals(2, changes)
        observer.close()
        verify { autoPreferences.unregisterOnSharedPreferenceChangeListener(autoListener.captured) }
        verify { audioPreferences.unregisterOnSharedPreferenceChangeListener(audioListener.captured) }
    }

    private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)
    private fun assertRejected(action: () -> Unit) {
        try { action(); fail("Malformed backup must be rejected") } catch (expected: IllegalArgumentException) { }
    }
}
