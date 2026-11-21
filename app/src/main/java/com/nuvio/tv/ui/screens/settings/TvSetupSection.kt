package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.setup.TvSetup
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/** Settings > Playback > "Set up for this TV". */
@Composable
internal fun PlaybackTvSetupSection(viewModel: TvSetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.check() }
    var applied by remember { mutableStateOf<Int?>(null) }

    SettingsNote(stringResource(R.string.tv_setup_intro))
    val facts = state.facts
    if (facts == null) {
        SettingsNote(stringResource(R.string.tv_setup_checking))
        return
    }
    SettingsNote(factsText(context, facts))

    val changes = state.items.count { it.needsChange }
    SettingsActionRow(
        title = stringResource(R.string.tv_setup_apply_title),
        subtitle = if (changes == 0) stringResource(R.string.tv_setup_apply_none)
        else context.resources.getQuantityString(R.plurals.tv_setup_apply_count, changes, changes),
        value = applied?.let { context.resources.getQuantityString(R.plurals.tv_setup_applied, it, it) },
        enabled = changes > 0,
        onClick = { viewModel.applyAll { applied = it } }
    )
    if (state.canUndo) {
        SettingsActionRow(
            title = stringResource(R.string.tv_setup_undo_title),
            subtitle = stringResource(R.string.tv_setup_undo_subtitle),
            onClick = {
                viewModel.undo()
                applied = null
            }
        )
    }
    state.items.forEach { item ->
        SettingsActionRow(
            title = item.title,
            subtitle = if (item.needsChange) {
                stringResource(R.string.tv_setup_row_change, item.now, item.suggested) + "\n" + item.reason
            } else {
                stringResource(R.string.tv_setup_row_keep, item.now, item.suggested) + "\n" + item.reason
            },
            value = stringResource(if (item.needsChange) R.string.tv_setup_badge_change else R.string.tv_setup_badge_ok),
            onClick = {}
        )
    }
    SettingsActionRow(
        title = stringResource(R.string.tv_setup_recheck_title),
        subtitle = stringResource(R.string.tv_setup_recheck_subtitle),
        onClick = {
            applied = null
            viewModel.check()
        }
    )
}

private fun factsText(context: Context, f: TvSetup.Facts): String {
    fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    val yes = s(R.string.tv_setup_yes)
    val no = s(R.string.tv_setup_no)
    val hdr = buildList {
        if (f.dolbyVision) add("Dolby Vision")
        if (f.hdr10) add("HDR10")
        if (f.hdr10Plus) add("HDR10+")
        if (f.hlg) add("HLG")
    }.ifEmpty { listOf(if (f.hdrKnown) s(R.string.tv_setup_hdr_none) else s(R.string.tv_setup_unknown)) }
    val rates = f.refreshRates.joinToString(", ") { r -> if (r % 1.0 == 0.0) r.toInt().toString() else "%.2f".format(r) }
    return listOf(
        s(R.string.tv_setup_fact_memory, "%.1f".format(f.ramGb)),
        s(R.string.tv_setup_fact_screen, f.screen ?: s(R.string.tv_setup_unknown), rates.ifEmpty { "?" }),
        s(R.string.tv_setup_fact_hdr, hdr.joinToString(", ")),
        s(
            R.string.tv_setup_fact_decoders,
            if (f.hevc) yes else no,
            if (f.av1) yes else no,
            if (f.vp9) yes else no,
            if (f.dvDecoder) yes else no
        ),
        s(R.string.tv_setup_fact_audio, f.audioRoute ?: s(R.string.tv_setup_unknown),
            f.passthrough.joinToString(", ").ifEmpty { s(R.string.tv_setup_passthrough_none) }),
        if (!f.av1) s(R.string.tv_setup_fact_av1_note) else null
    ).filterNotNull().joinToString("\n")
}

@HiltViewModel
class TvSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettings: PlayerSettingsDataStore,
    private val liveTv: LiveTvPreferences
) : ViewModel() {

    data class State(
        val facts: TvSetup.Facts? = null,
        val items: List<TvSetup.Item> = emptyList(),
        val canUndo: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val store by lazy { context.getSharedPreferences("tv_setup", Context.MODE_PRIVATE) }

    fun check() {
        viewModelScope.launch {
            val facts = withContext(Dispatchers.Default) { TvSetup.readFacts(context) }
            val player = playerSettings.playerSettings.first()
            val live = runCatching { liveTv.settings.first() }.getOrNull()
            _state.value = State(
                facts = facts,
                items = TvSetup.suggestions(context, facts, player, live),
                canUndo = store.contains(UNDO)
            )
        }
    }

    /** Applies every suggestion based on a fact; what was there before is saved for Undo. */
    fun applyAll(done: (Int) -> Unit) {
        viewModelScope.launch {
            val player = playerSettings.playerSettings.first()
            val live = runCatching { liveTv.settings.first() }.getOrNull()
            val changes = _state.value.items.mapNotNull { it.change }
            if (changes.isEmpty()) return@launch
            val before = JSONObject()
            changes.forEach { change ->
                when (change) {
                    is TvSetup.Change.FrameRate -> {
                        before.put("afr", player.frameRateMatchingMode.name)
                        playerSettings.setFrameRateMatchingMode(change.mode)
                    }
                    is TvSetup.Change.LiveFrameRate -> {
                        live?.let { before.put("live_afr", it.frameRateMode) }
                        liveTv.updateSettings { it.copy(frameRateMode = change.mode) }
                    }
                    is TvSetup.Change.Dv7 -> {
                        before.put("dv7", player.dv7HandlingMode.name)
                        playerSettings.setDv7HandlingMode(change.mode)
                    }
                    is TvSetup.Change.StripHdr10Plus -> {
                        before.put("hdr10plus", player.stripHdr10PlusSei)
                        playerSettings.setStripHdr10PlusSei(change.on)
                    }
                    is TvSetup.Change.ForceAc3 -> {
                        before.put("ac3", player.forceOpticalPassthrough)
                        playerSettings.setForceOpticalPassthrough(change.on)
                    }
                    is TvSetup.Change.ManagedBuffer -> {
                        before.put("managed", player.bufferBudgetManaged)
                        playerSettings.setBufferBudgetManaged(change.on)
                    }
                    is TvSetup.Change.LargeBuffer -> {
                        before.put("large", player.allowLargeTargetBuffer)
                        playerSettings.setAllowLargeTargetBuffer(change.on)
                    }
                }
            }
            store.edit().putString(UNDO, before.toString()).apply()
            done(changes.size)
            check()
        }
    }

    /** Puts back what Apply all changed. */
    fun undo() {
        viewModelScope.launch {
            val json = store.getString(UNDO, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json != null) {
                if (json.has("afr")) runCatching { FrameRateMatchingMode.valueOf(json.getString("afr")) }.getOrNull()
                    ?.let { playerSettings.setFrameRateMatchingMode(it) }
                if (json.has("live_afr")) {
                    val mode = json.getString("live_afr")
                    liveTv.updateSettings { it.copy(frameRateMode = mode) }
                }
                if (json.has("dv7")) runCatching { Dv7HandlingMode.valueOf(json.getString("dv7")) }.getOrNull()
                    ?.let { playerSettings.setDv7HandlingMode(it) }
                if (json.has("hdr10plus")) playerSettings.setStripHdr10PlusSei(json.getBoolean("hdr10plus"))
                if (json.has("ac3")) playerSettings.setForceOpticalPassthrough(json.getBoolean("ac3"))
                if (json.has("managed")) playerSettings.setBufferBudgetManaged(json.getBoolean("managed"))
                if (json.has("large")) playerSettings.setAllowLargeTargetBuffer(json.getBoolean("large"))
            }
            store.edit().remove(UNDO).apply()
            check()
        }
    }

    private companion object {
        const val UNDO = "undo"
    }
}
