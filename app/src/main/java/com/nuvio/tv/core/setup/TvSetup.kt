package com.nuvio.tv.core.setup

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioManager
import android.view.Display
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.core.player.PlaybackDeviceSupport
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.ui.screens.player.AudioOutputRouteDetector
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import kotlin.math.roundToInt

/**
 * "Set up for this TV" (Settings > Playback): reads what this box, screen and sound system support and
 * suggests settings, each with the reason. Our own version of Nuvio Arc's device assessment (GPL-3): it
 * reads facts only (no stream measuring), and only suggestions based on a fact are ever applied.
 */
object TvSetup {

    /** Memory below this: no "large buffer" (the same line Nuvio Arc uses). */
    private const val LARGE_BUFFER_RAM_BYTES = 3_200_000_000L

    data class Facts(
        val ramGb: Double,
        val screen: String?,
        val refreshRates: List<Double>,
        val frameRateSwitching: Boolean,
        val resolutionSwitching: Boolean,
        val screenKnown: Boolean,
        val dolbyVision: Boolean,
        val hdr10: Boolean,
        val hdr10Plus: Boolean,
        val hlg: Boolean,
        val hdrKnown: Boolean,
        val hevc: Boolean,
        val av1: Boolean,
        val vp9: Boolean,
        val dvDecoder: Boolean,
        val audioRoute: String?,
        val hdmiAudio: Boolean,
        val passthrough: List<String>
    ) {
        val hdrScreen: Boolean get() = dolbyVision || hdr10 || hdr10Plus || hlg
    }

    /** One suggestion. [apply] is null when it's only advice (a matter of taste, or a fact we can't read). */
    data class Item(
        val key: String,
        val title: String,
        val now: String,
        val suggested: String,
        val reason: String,
        val change: Change?
    ) {
        val needsChange: Boolean get() = change != null
    }

    sealed interface Change {
        data class FrameRate(val mode: FrameRateMatchingMode) : Change
        data class LiveFrameRate(val mode: String) : Change
        data class Dv7(val mode: Dv7HandlingMode) : Change
        data class StripHdr10Plus(val on: Boolean) : Change
        data class ForceAc3(val on: Boolean) : Change
        data class ManagedBuffer(val on: Boolean) : Change
        data class LargeBuffer(val on: Boolean) : Change
    }

    fun readFacts(context: Context): Facts {
        val app = context.applicationContext
        val display = runCatching {
            app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val modes = runCatching { display?.supportedModes?.toList() }.getOrNull().orEmpty()
        val current = runCatching { display?.mode }.getOrNull()
        val (afr, res) = if (current != null) DisplayCapabilities.deriveSupport(modes, current.modeId) else false to false
        val rates = modes
            .filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
            .map { (it.refreshRate * 100).roundToInt() / 100.0 }
            .distinct()
            .sorted()
        val hdrTypes = runCatching { display?.hdrCapabilities?.supportedHdrTypes }.getOrNull()
        val audio = runCatching { AudioOutputRouteDetector.detect(app) }.getOrNull()
        val routeKey = audio?.key.orEmpty()
        return Facts(
            ramGb = NuvioExoPlayerPerformanceHelper.getDevicePhysicalRamBytes(app) / 1_000_000_000.0,
            screen = current?.let { "${it.physicalWidth}×${it.physicalHeight}" },
            refreshRates = rates,
            frameRateSwitching = afr,
            resolutionSwitching = res,
            screenKnown = current != null,
            dolbyVision = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true,
            hdr10 = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true,
            hdr10Plus = android.os.Build.VERSION.SDK_INT >= 29 &&
                hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) == true,
            hlg = hdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HLG) == true,
            hdrKnown = hdrTypes != null,
            hevc = PlaybackDeviceSupport.hasHardwareDecoder("video/hevc"),
            av1 = PlaybackDeviceSupport.hardwareAv1,
            vp9 = PlaybackDeviceSupport.hasHardwareDecoder("video/x-vnd.on2.vp9"),
            dvDecoder = PlaybackDeviceSupport.hasHardwareDecoder("video/dolby-vision"),
            audioRoute = audio?.label,
            hdmiAudio = routeKey.startsWith("type:hdmi"),
            passthrough = passthroughFormats(app)
        )
    }

    private fun passthroughFormats(context: Context): List<String> = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return@runCatching emptyList()
        val encodings = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == android.media.AudioDeviceInfo.TYPE_HDMI || it.type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC ||
                (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC) }
            .flatMap { it.encodings.toList() }
            .toSet()
        buildList {
            if (AudioFormat.ENCODING_AC3 in encodings) add("Dolby Digital")
            if (AudioFormat.ENCODING_E_AC3 in encodings) add("Dolby Digital Plus")
            if (android.os.Build.VERSION.SDK_INT >= 28 && AudioFormat.ENCODING_E_AC3_JOC in encodings) add("Atmos (DD+)")
            if (android.os.Build.VERSION.SDK_INT >= 25 && AudioFormat.ENCODING_DOLBY_TRUEHD in encodings) add("TrueHD")
            if (AudioFormat.ENCODING_DTS in encodings) add("DTS")
            if (AudioFormat.ENCODING_DTS_HD in encodings) add("DTS-HD")
        }
    }.getOrDefault(emptyList())

    fun suggestions(context: Context, facts: Facts, player: PlayerSettings, live: LiveTvSettings?): List<Item> {
        fun s(id: Int, vararg args: Any) = context.getString(id, *args)
        val on = s(R.string.tv_setup_on)
        val off = s(R.string.tv_setup_off)
        val leave = s(R.string.tv_setup_leave)
        val yourCall = s(R.string.tv_setup_your_call)
        val rates = facts.refreshRates.joinToString(" / ") { r -> if (r % 1.0 == 0.0) r.toInt().toString() else "%.3f".format(r).trimEnd('0') }
        fun afrLabel(mode: FrameRateMatchingMode) = when (mode) {
            FrameRateMatchingMode.OFF -> off
            FrameRateMatchingMode.START -> s(R.string.playback_afr_on_start)
            FrameRateMatchingMode.START_STOP -> s(R.string.playback_afr_on_start_stop)
        }
        val items = mutableListOf<Item>()

        // Frame rate matching (movies and shows): only when the screen can switch refresh rate.
        val afrTitle = s(R.string.tv_setup_afr_title)
        items += when {
            !facts.screenKnown -> Item("afr", afrTitle, afrLabel(player.frameRateMatchingMode), leave, s(R.string.tv_setup_screen_unknown), null)
            facts.frameRateSwitching && player.frameRateMatchingMode == FrameRateMatchingMode.OFF ->
                Item("afr", afrTitle, off, afrLabel(FrameRateMatchingMode.START_STOP), s(R.string.tv_setup_afr_on_reason, rates),
                    Change.FrameRate(FrameRateMatchingMode.START_STOP))
            facts.frameRateSwitching ->
                Item("afr", afrTitle, afrLabel(player.frameRateMatchingMode), afrLabel(player.frameRateMatchingMode), s(R.string.tv_setup_afr_already, rates), null)
            player.frameRateMatchingMode != FrameRateMatchingMode.OFF ->
                Item("afr", afrTitle, afrLabel(player.frameRateMatchingMode), off, s(R.string.tv_setup_afr_single, rates),
                    Change.FrameRate(FrameRateMatchingMode.OFF))
            else -> Item("afr", afrTitle, off, off, s(R.string.tv_setup_afr_single, rates), null)
        }

        // The same for Live TV, which has its own switch.
        if (live != null && facts.screenKnown) {
            val liveTitle = s(R.string.tv_setup_live_afr_title)
            val liveNow = when (live.frameRateMode) {
                "START" -> s(R.string.playback_afr_on_start)
                "START_STOP" -> s(R.string.playback_afr_on_start_stop)
                else -> off
            }
            items += when {
                facts.frameRateSwitching && live.frameRateMode == "OFF" ->
                    Item("live_afr", liveTitle, off, s(R.string.playback_afr_on_start_stop), s(R.string.tv_setup_live_afr_on_reason),
                        Change.LiveFrameRate("START_STOP"))
                facts.frameRateSwitching -> Item("live_afr", liveTitle, liveNow, liveNow, s(R.string.tv_setup_afr_already, rates), null)
                live.frameRateMode != "OFF" ->
                    Item("live_afr", liveTitle, liveNow, off, s(R.string.tv_setup_afr_single, rates), Change.LiveFrameRate("OFF"))
                else -> Item("live_afr", liveTitle, off, off, s(R.string.tv_setup_afr_single, rates), null)
            }
        }

        // Resolution matching is taste (which one upscales: the TV or the box), so it's only explained.
        items += Item(
            "res_match", s(R.string.tv_setup_res_title),
            if (player.resolutionMatchingEnabled) on else off,
            if (facts.resolutionSwitching) yourCall else leave,
            s(if (facts.resolutionSwitching) R.string.tv_setup_res_choice else R.string.tv_setup_res_inert),
            null
        )

        // Dolby Vision Profile 7: Auto picks the right handling for this screen.
        val dvTitle = s(R.string.tv_setup_dv7_title)
        val dvReason = when {
            !facts.hdrKnown -> s(R.string.tv_setup_hdr_unknown)
            facts.dolbyVision -> s(R.string.tv_setup_dv7_dv_screen)
            facts.hdrScreen -> s(R.string.tv_setup_dv7_hdr_screen)
            else -> s(R.string.tv_setup_dv7_sdr_screen)
        }
        items += if (player.dv7HandlingMode != Dv7HandlingMode.AUTO) {
            Item("dv7", dvTitle, dv7Label(context, player.dv7HandlingMode), s(R.string.tv_setup_auto), dvReason,
                if (facts.hdrKnown) Change.Dv7(Dv7HandlingMode.AUTO) else null)
        } else {
            Item("dv7", dvTitle, s(R.string.tv_setup_auto), s(R.string.tv_setup_auto), dvReason, null)
        }

        // HDR10+ extra data: drop it when the screen does HDR but not HDR10+ (some TVs flicker on it).
        val stripTitle = s(R.string.tv_setup_hdr10plus_title)
        val stripNow = if (player.stripHdr10PlusSei) on else off
        items += when {
            !facts.hdrKnown -> Item("hdr10plus", stripTitle, stripNow, leave, s(R.string.tv_setup_hdr_unknown), null)
            !facts.hdrScreen -> Item("hdr10plus", stripTitle, stripNow, leave, s(R.string.tv_setup_hdr10plus_sdr), null)
            facts.hdr10Plus -> Item("hdr10plus", stripTitle, stripNow, off, s(R.string.tv_setup_hdr10plus_supported),
                if (player.stripHdr10PlusSei) Change.StripHdr10Plus(false) else null)
            else -> Item("hdr10plus", stripTitle, stripNow, on, s(R.string.tv_setup_hdr10plus_unsupported),
                if (!player.stripHdr10PlusSei) Change.StripHdr10Plus(true) else null)
        }

        // Force Dolby Digital (AC-3): on an HDMI connection it only turns lossless sound into lossy.
        val ac3Title = s(R.string.tv_setup_ac3_title)
        val ac3Now = if (player.forceOpticalPassthrough) on else off
        items += if (facts.hdmiAudio) {
            Item("ac3", ac3Title, ac3Now, off, s(R.string.tv_setup_ac3_hdmi, facts.audioRoute ?: "HDMI"),
                if (player.forceOpticalPassthrough) Change.ForceAc3(false) else null)
        } else {
            Item("ac3", ac3Title, ac3Now, leave, s(R.string.tv_setup_ac3_unknown), null)
        }

        // Buffer: let the app size it from this box's memory.
        val ram = "%.1f".format(facts.ramGb)
        items += Item(
            "managed_buffer", s(R.string.tv_setup_managed_title),
            if (player.bufferBudgetManaged) on else off, on,
            s(R.string.tv_setup_managed_reason, ram),
            if (!player.bufferBudgetManaged) Change.ManagedBuffer(true) else null
        )
        val smallBox = facts.ramGb * 1_000_000_000.0 < LARGE_BUFFER_RAM_BYTES
        items += Item(
            "large_buffer", s(R.string.tv_setup_large_title),
            if (player.allowLargeTargetBuffer) on else off,
            if (smallBox) off else leave,
            s(if (smallBox) R.string.tv_setup_large_small_box else R.string.tv_setup_large_ok, ram),
            if (smallBox && player.allowLargeTargetBuffer) Change.LargeBuffer(false) else null
        )

        // Tunneled playback: can't be judged from here, so only a note.
        if (player.tunnelingEnabled) {
            items += Item("tunneling", s(R.string.tv_setup_tunneling_title), on, s(R.string.tv_setup_check_yourself),
                s(R.string.tv_setup_tunneling_reason), null)
        }
        return items
    }

    private fun dv7Label(context: Context, mode: Dv7HandlingMode): String = context.getString(
        when (mode) {
            Dv7HandlingMode.AUTO -> R.string.tv_setup_auto
            Dv7HandlingMode.HDR10_BASE_LAYER -> R.string.tv_setup_dv7_hdr10
            Dv7HandlingMode.DV81_LIBDOVI -> R.string.tv_setup_dv7_convert
            Dv7HandlingMode.STRIP_DV -> R.string.tv_setup_dv7_strip
            Dv7HandlingMode.OFF -> R.string.tv_setup_off
        }
    )
}
