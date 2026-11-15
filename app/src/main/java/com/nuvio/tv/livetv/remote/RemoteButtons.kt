package com.nuvio.tv.livetv.remote

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.tv.livetv.calendar.CalendarEntryPoint
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** An extra button added with "Add a button" (a key this app has no name for). */
data class RemoteLearned(val code: Int, val name: String)

/** screen id → button id → press id → action id. Only what the user changed is in here. */
typealias RemoteMap = Map<String, Map<String, Map<String, String>>>

data class RemoteState(
    val map: RemoteMap = emptyMap(),
    /** Key codes this TV has really received (a button is only offered once it has been seen). */
    val seen: Set<Int> = emptySet(),
    val learned: List<RemoteLearned> = emptyList(),
    /** The last button came from a game controller. */
    val pad: Boolean = false,
    val device: String = ""
) {
    fun changed(screen: String): Int = map[screen]?.values?.sumOf { it.size } ?: 0
    val changedTotal: Int get() = map.values.sumOf { s -> s.values.sumOf { it.size } }
    fun of(screen: String, button: String): Map<String, String> = map[screen]?.get(button) ?: emptyMap()
}

/**
 * Remote Buttons: the user's own button choices, kept on this TV for each profile.
 *
 * How a press travels: a screen asks its [RemoteKeys] about each key it receives. If the user
 * never changed that button on that screen, the answer is "not mine" straight away and the
 * screen carries on exactly as before. Only a changed button is taken over.
 */
object RemoteButtons {
    const val HOLD_MS = 550L
    const val DOUBLE_MS = 400L

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: LiveTvPreferences? = null
    private var started = false
    @Volatile private var learnedButtons: Map<Int, RemoteButton> = emptyMap()

    fun start(context: Context) {
        if (started) return
        started = true
        runCatching {
            val p = EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java).liveTvPreferences()
            prefs = p
            main.launch {
                p.remoteStored.collect { (map, seen, learned) ->
                    val list = decodeLearned(learned)
                    learnedButtons = list.associate { it.code to RemoteButton("K${it.code}", it.name, "KEY", listOf(it.code)) }
                    _state.value = _state.value.copy(
                        map = decodeMap(map),
                        seen = _state.value.seen + seen.mapNotNull { it.toIntOrNull() },
                        learned = list
                    )
                }
            }
        }
    }

    fun buttonFor(code: Int): RemoteButton? = RemoteCatalog.buttonForCode(code) ?: learnedButtons[code]
    fun button(id: String): RemoteButton? = RemoteCatalog.button(id) ?: learnedButtons.values.firstOrNull { it.id == id }
    /** Every button there is: the known ones, then the added ones. */
    fun allButtons(): List<RemoteButton> = RemoteCatalog.BUTTONS + learnedButtons.values

    // ------------------------------------------------------------------ what this TV has seen

    /** Called for every key the app receives: notes the button as "seen" and which kind of device sent it. */
    fun noteKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val code = event.keyCode
        val fallback = event.flags and KeyEvent.FLAG_FALLBACK != 0
        var s = _state.value
        val pad = when {
            KeyEvent.isGamepadButton(code) -> true
            fallback -> s.pad
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU ||
                code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> false
            else -> s.pad
        }
        val name = runCatching { event.device?.name }.getOrNull().orEmpty()
        if (pad != s.pad || (name.isNotEmpty() && name != s.device)) {
            s = s.copy(pad = pad, device = name.ifEmpty { s.device })
            _state.value = s
        }
        if (code in RemoteCatalog.NEVER || code in s.seen || RemoteCatalog.buttonForCode(code) == null) return
        _state.value = s.copy(seen = s.seen + code)
        io.launch { runCatching { prefs?.addRemoteSeen(setOf(code.toString())) } }
    }

    // ------------------------------------------------------------------ the rest of a press

    private var swallowCode = 0

    /** After a button did its new job: the rest of that press (repeats, letting go) does nothing. */
    fun swallow(code: Int) { swallowCode = code }

    fun swallowed(event: KeyEvent): Boolean {
        if (swallowCode == 0 || event.keyCode != swallowCode) return false
        // A fresh press of the same button (the release went to a dialog): start again.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { swallowCode = 0; return false }
        if (event.action == KeyEvent.ACTION_UP) swallowCode = 0
        return true
    }

    // ------------------------------------------------------------------ which screen is up

    private val stack = ArrayList<RemoteKeys>()
    internal fun enter(keys: RemoteKeys) { stack.remove(keys); stack.add(keys) }
    internal fun leave(keys: RemoteKeys) { stack.remove(keys) }

    /** Set by the app's main screen: the page showing, and how to open another one. */
    @Volatile var route: String? = null
    @Volatile var navigate: ((String) -> Unit)? = null

    private val browseRoutes: Set<String> by lazy {
        setOf(Screen.Home.route, Screen.OnDemand.route, Screen.Library.route, Screen.Discover.route, Screen.Search.route)
    }
    private val browseKeys by lazy { RemoteKeys(main, RemoteCatalog.BROWSE) }
    private val everywhereKeys by lazy { RemoteKeys(main, RemoteCatalog.EVERYWHERE) }

    /**
     * Buttons set under "Browsing" and "Everywhere". A screen's own choice for the button always
     * comes first; "Everywhere" only gets buttons the screen showing left alone.
     */
    fun rootKey(event: KeyEvent): Boolean {
        val s = _state.value
        if (s.map.isEmpty()) return false
        val btn = buttonFor(event.keyCode) ?: return false
        val top = stack.lastOrNull()
        val here: RemoteScreen? = top?.screen ?: if (route in browseRoutes) RemoteCatalog.BROWSE else null
        if (here != null && s.of(here.id, btn.id).isNotEmpty()) {
            return if (top == null) browseKeys.onKey(event, nav = btn.nav) else false
        }
        if (route == null) return false
        return everywhereKeys.onKey(event, nav = btn.nav)
    }

    private val goRoutes: Map<String, () -> String> = mapOf(
        "go.live_tv" to { Screen.LiveTv.route }, "go.home" to { Screen.Home.route }, "go.sports" to { Screen.Sports.route },
        "go.on_demand" to { Screen.OnDemand.route }, "go.search" to { Screen.Search.route },
        "go.recordings" to { Screen.Recordings.route }, "go.calendar" to { Screen.Calendar.route },
        "go.multiview" to { Screen.LiveTvMultiview.route }, "go.settings" to { Screen.Settings.route }
    )

    internal fun go(id: String): Boolean {
        val target = goRoutes[id]?.invoke() ?: return false
        val nav = navigate ?: return false
        return runCatching { nav(target) }.isSuccess
    }

    // ------------------------------------------------------------------ changing things

    private fun commit(map: RemoteMap) {
        val clean = map.mapValues { (_, buttons) -> buttons.filterValues { it.isNotEmpty() } }.filterValues { it.isNotEmpty() }
        _state.value = _state.value.copy(map = clean)
        val json = if (clean.isEmpty()) null else encodeMap(clean)
        io.launch { runCatching { prefs?.setRemoteMap(json) } }
    }

    /** [action] null puts that press back to standard. */
    fun set(screen: String, button: String, press: RemotePress, action: String?) {
        val map = _state.value.map
        val buttons = map[screen].orEmpty()
        val presses = buttons[button].orEmpty().toMutableMap()
        if (action == null) presses.remove(press.id) else presses[press.id] = action
        commit(map + (screen to (buttons + (button to presses))))
    }

    fun resetButton(screen: String, button: String) {
        val map = _state.value.map
        commit(map + (screen to (map[screen].orEmpty() - button)))
    }

    fun resetScreen(screen: String) = commit(_state.value.map - screen)
    fun resetAll() = commit(emptyMap())

    /** Gives a controller's spare buttons their suggested jobs (only where the user set nothing). */
    fun applySuggestedPad() {
        var map = _state.value.map
        RemoteCatalog.PAD_SUGGESTED.forEach { (screen, picks) ->
            var buttons = map[screen].orEmpty()
            picks.forEach { (button, action) ->
                if (buttons[button].isNullOrEmpty()) buttons = buttons + (button to mapOf(RemotePress.PRESS.id to action))
            }
            map = map + (screen to buttons)
        }
        commit(map)
    }

    fun profileId(): Int = prefs?.remoteProfileId ?: 1
    fun profiles(): List<Pair<Int, String>> = prefs?.remoteProfiles().orEmpty()

    fun copyFromProfile(id: Int) {
        val p = prefs ?: return
        io.launch {
            val json = runCatching { p.remoteMapOf(id) }.getOrNull()
            main.launch { commit(decodeMap(json)) }
        }
    }

    /** Adds a button this app has no name for. False when it can't be used (system buttons, ones already listed). */
    fun learn(code: Int): Boolean {
        if (code in RemoteCatalog.NEVER || buttonFor(code) != null) return false
        val name = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
        val list = _state.value.learned + RemoteLearned(code, name)
        saveLearned(list)
        return true
    }

    fun forget(code: Int) {
        saveLearned(_state.value.learned.filter { it.code != code })
        val id = "K$code"
        commit(_state.value.map.mapValues { (_, buttons) -> buttons - id })
    }

    private fun saveLearned(list: List<RemoteLearned>) {
        learnedButtons = list.associate { it.code to RemoteButton("K${it.code}", it.name, "KEY", listOf(it.code)) }
        _state.value = _state.value.copy(learned = list)
        val json = if (list.isEmpty()) null else JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("code", it.code).put("name", it.name)) }
        }.toString()
        io.launch { runCatching { prefs?.setRemoteLearned(json) } }
    }

    // ------------------------------------------------------------------ stored form

    private fun encodeMap(map: RemoteMap): String {
        val screens = JSONObject()
        map.forEach { (screen, buttons) ->
            val bo = JSONObject()
            buttons.forEach { (button, presses) ->
                val po = JSONObject()
                presses.forEach { (press, action) -> po.put(press, action) }
                bo.put(button, po)
            }
            screens.put(screen, bo)
        }
        return JSONObject().put("v", 1).put("screens", screens).toString()
    }

    /** Anything unreadable or unknown is simply left out: that button is then standard again. */
    private fun decodeMap(json: String?): RemoteMap {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val screens = JSONObject(json).optJSONObject("screens") ?: return emptyMap()
            buildMap<String, Map<String, Map<String, String>>> {
                screens.keys().forEach { screen ->
                    val bo = screens.optJSONObject(screen) ?: return@forEach
                    val buttons = buildMap<String, Map<String, String>> {
                        bo.keys().forEach { button ->
                            val po = bo.optJSONObject(button) ?: return@forEach
                            val presses = buildMap<String, String> {
                                po.keys().forEach { press -> po.optString(press).takeIf { it.isNotEmpty() }?.let { put(press, it) } }
                            }
                            if (presses.isNotEmpty()) put(button, presses)
                        }
                    }
                    if (buttons.isNotEmpty()) put(screen, buttons)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun decodeLearned(json: String?): List<RemoteLearned> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val code = o.optInt("code", 0)
                if (code <= 0 || code in RemoteCatalog.NEVER) null else RemoteLearned(code, o.optString("name").ifEmpty { "Key $code" })
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * One screen's side of Remote Buttons. The screen gives [run] (action id → its own function,
 * true when it did something) and calls [onKey] from its key handler.
 */
class RemoteKeys internal constructor(private val scope: CoroutineScope, screen: RemoteScreen) {
    @Volatile var screen: RemoteScreen = screen
    var run: (String, KeyEvent?) -> Boolean = { _, _ -> false }

    private var downId: String? = null
    private var holdFired = false
    private var holdJob: Job? = null
    private var tapId: String? = null
    private var tapJob: Job? = null
    /** A press whose new job couldn't run (nothing playing, say): the screen gets the whole press. */
    private var passCode = 0

    private fun choices(sc: RemoteScreen, slot: RemoteSlot, raw: Map<String, String>): Map<RemotePress, String> {
        val out = HashMap<RemotePress, String>(3)
        for (press in RemotePress.entries) {
            val id = raw[press.id] ?: continue
            if (!slot.allows(press)) continue
            val action = sc.action(id) ?: continue
            if (slot.goOnly && !action.goes) continue
            out[press] = id
        }
        return out
    }

    /** True when the user changed this button here (so the screen's own handling of it should stand down). */
    fun overrides(event: KeyEvent): Boolean {
        val btn = RemoteButtons.buttonFor(event.keyCode) ?: return false
        return RemoteButtons.state.value.of(screen.id, btn.id).isNotEmpty()
    }

    private fun fire(id: String?, event: KeyEvent?): Boolean {
        if (id == null || id == RemoteCatalog.NONE.id) return true
        if (id.startsWith("go.")) return RemoteButtons.go(id)
        return runCatching { run(id, event) }.getOrDefault(false)
    }

    /**
     * @param nav the caller's spot handles OK / arrows / Back ([RemoteButton.nav]) or the other buttons
     * @param enabled false while a menu, dialog or list is open: buttons then keep their usual job
     * @return true when the key was taken (the screen must not handle it too)
     */
    fun onKey(event: KeyEvent, nav: Boolean, enabled: Boolean = true): Boolean {
        val sc = screen
        val raw0 = RemoteButtons.state.value.map[sc.id] ?: return false
        val btn = RemoteButtons.buttonFor(event.keyCode) ?: return false
        if (btn.nav != nav) return false
        val raw = raw0[btn.id]
        if (raw.isNullOrEmpty()) return false
        if (passCode == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) passCode = 0
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
            passCode = 0
        }
        if (!enabled) return false
        val slot = sc.slot(btn) ?: return false
        val picks = choices(sc, slot, raw)
        if (picks.isEmpty()) return false
        val press = picks[RemotePress.PRESS]
        val hold = picks[RemotePress.HOLD] ?: slot.stdHold
        val double = picks[RemotePress.DOUBLE]
        val code = event.keyCode

        if (hold == null && double == null) {
            // Only the press was changed: act right away, like the screens do.
            val action = sc.action(press) ?: return false
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (action.repeats) {
                if (!fire(press, event) && event.repeatCount == 0) { passCode = code; return false }
                return true
            }
            if (event.repeatCount > 0) return true
            if (!fire(press, event)) { passCode = code; return false }
            RemoteButtons.swallow(code)
            return true
        }

        // A hold or a double press is in play: a short press is judged when the button is let go.
        val standard = press ?: slot.std
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                if (double != null && tapId == btn.id) {
                    tapJob?.cancel(); tapId = null
                    fire(double, event)
                    RemoteButtons.swallow(code)
                    return true
                }
                downId = btn.id
                holdFired = false
                holdJob?.cancel()
                holdJob = if (hold == null) null else scope.launch {
                    delay(RemoteButtons.HOLD_MS)
                    if (downId == btn.id && !holdFired) {
                        holdFired = true
                        fire(hold, null)
                        RemoteButtons.swallow(code)
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                holdJob?.cancel()
                if (downId != btn.id) return true
                downId = null
                if (holdFired) { holdFired = false; return true }
                if (hold != null && event.eventTime - event.downTime >= RemoteButtons.HOLD_MS) {
                    fire(hold, null)
                    return true
                }
                if (double != null) {
                    tapId = btn.id
                    tapJob?.cancel()
                    tapJob = scope.launch {
                        delay(RemoteButtons.DOUBLE_MS)
                        if (tapId == btn.id) { tapId = null; fire(standard, null) }
                    }
                } else fire(standard, event)
            }
        }
        return true
    }
}

/** A screen's [RemoteKeys]; while the screen is showing it is the one "Everywhere" defers to. */
@Composable
fun rememberRemoteKeys(screen: RemoteScreen): RemoteKeys {
    val scope = rememberCoroutineScope()
    val keys = remember { RemoteKeys(scope, screen) }
    keys.screen = screen
    DisposableEffect(keys) {
        RemoteButtons.enter(keys)
        onDispose { RemoteButtons.leave(keys) }
    }
    return keys
}
