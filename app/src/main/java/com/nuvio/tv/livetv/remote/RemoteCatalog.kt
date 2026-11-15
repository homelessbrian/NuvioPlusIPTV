package com.nuvio.tv.livetv.remote

import android.view.KeyEvent

/**
 * Remote Buttons (Settings → Remote Buttons): what can be changed, where, and to what.
 *
 * Nothing here does anything by itself. Each screen keeps its own functions; this file only
 * names them, so a button can be pointed at a different one. A button nobody changed is never
 * touched: the screen handles it exactly as before.
 */

/** A press, a hold (about half a second) or two quick presses. */
enum class RemotePress(val id: String, val label: String) {
    PRESS("press", "Press"), HOLD("hold", "Hold"), DOUBLE("double", "Double press")
}

/**
 * One physical button. [codes] are the Android key codes it can arrive as. [nav] buttons move
 * you around (OK, arrows, Back): a screen only lets those be changed where nothing is highlighted.
 */
class RemoteButton(
    val id: String,
    val label: String,
    /** A short tag for lists ("MENU", "CH+"). */
    val tag: String,
    val codes: List<Int>,
    val nav: Boolean = false,
    /** A game controller's button. */
    val pad: Boolean = false
)

/** What a screen does with a button out of the box, and which kinds of press may be changed. */
class RemoteSlot(
    val button: String,
    /** The standard press, when it is one named action. */
    val std: String? = null,
    /** The standard press in words, when it is more than one named action (then only Press can be changed). */
    val stdText: String? = null,
    /** The standard hold, when there is one. */
    val stdHold: String? = null,
    val hold: Boolean = true,
    val double: Boolean = true,
    /** Back: only actions that take you somewhere, so Back always leads out. */
    val goOnly: Boolean = false
) {
    val passthrough: Boolean get() = std == null && stdText != null
    fun allows(press: RemotePress): Boolean = when (press) {
        RemotePress.PRESS -> true
        RemotePress.HOLD -> hold && !passthrough
        RemotePress.DOUBLE -> double && !passthrough
    }
}

class RemoteAction(
    val id: String,
    val label: String,
    val category: String,
    /** Fires again and again while the button is held (channel up, skip). */
    val repeats: Boolean = false,
    /** Takes you to another place (the only kind Back may be given). */
    val goes: Boolean = false
)

class RemoteScreen(
    val id: String,
    val title: String,
    val about: String,
    private val own: List<RemoteSlot>,
    val actions: List<RemoteAction>,
    /** Buttons that do nothing anywhere (color keys, a controller's extra buttons) are offered too. */
    private val spare: Boolean = true
) {
    private val byButton = own.associateBy { it.button }
    fun slot(button: RemoteButton): RemoteSlot? =
        byButton[button.id] ?: if (spare && (button.id in RemoteCatalog.SPARE || button.id.startsWith("K"))) RemoteSlot(button.id) else null
    fun action(id: String?): RemoteAction? = when (id) {
        null -> null
        RemoteCatalog.NONE.id -> RemoteCatalog.NONE
        else -> actions.firstOrNull { it.id == id } ?: RemoteCatalog.GO.firstOrNull { it.id == id }
    }
    /** Every action this screen offers for [slot]. */
    fun choices(slot: RemoteSlot): List<RemoteAction> {
        val all = actions + RemoteCatalog.GO
        return if (slot.goOnly) all.filter { it.goes } else all + RemoteCatalog.NONE
    }
    val ownButtons: List<String> get() = own.map { it.button }
}

object RemoteCatalog {
    val NONE = RemoteAction("none", "Nothing", "Other")

    private fun b(id: String, label: String, tag: String, vararg codes: Int, nav: Boolean = false, pad: Boolean = false) =
        RemoteButton(id, label, tag, codes.toList(), nav, pad)

    val BUTTONS: List<RemoteButton> = listOf(
        b("MENU", "Menu", "MENU", KeyEvent.KEYCODE_MENU),
        b("PLAY_PAUSE", "Play/Pause", "PLAY", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE),
        b("REWIND", "Rewind", "REW", KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD),
        b("FAST_FORWARD", "Fast forward", "FWD", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD),
        b("CH_UP", "Channel up", "CH+", KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP),
        b("CH_DOWN", "Channel down", "CH−", KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN),
        b("INFO", "Info", "INFO", KeyEvent.KEYCODE_INFO),
        b("BACK", "Back", "BACK", KeyEvent.KEYCODE_BACK, nav = true),
        b("OK", "OK", "OK", KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, nav = true),
        b("UP", "Up", "▲", KeyEvent.KEYCODE_DPAD_UP, nav = true),
        b("DOWN", "Down", "▼", KeyEvent.KEYCODE_DPAD_DOWN, nav = true),
        b("LEFT", "Left", "◀", KeyEvent.KEYCODE_DPAD_LEFT, nav = true),
        b("RIGHT", "Right", "▶", KeyEvent.KEYCODE_DPAD_RIGHT, nav = true),
        b("SEARCH", "Search", "FIND", KeyEvent.KEYCODE_SEARCH),
        b("LAST", "Last channel", "LAST", KeyEvent.KEYCODE_LAST_CHANNEL),
        b("CAPTIONS", "Captions", "CC", KeyEvent.KEYCODE_CAPTIONS),
        b("RED", "Red", "RED", KeyEvent.KEYCODE_PROG_RED),
        b("GREEN", "Green", "GRN", KeyEvent.KEYCODE_PROG_GREEN),
        b("YELLOW", "Yellow", "YEL", KeyEvent.KEYCODE_PROG_YELLOW),
        b("BLUE", "Blue", "BLU", KeyEvent.KEYCODE_PROG_BLUE),
        b("GUIDE", "Guide", "GUIDE", KeyEvent.KEYCODE_GUIDE),
        b("RECORD", "Record", "REC", KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_DVR),
        b("PAD_Y", "Y", "Y", KeyEvent.KEYCODE_BUTTON_Y, pad = true),
        b("PAD_X", "X", "X", KeyEvent.KEYCODE_BUTTON_X, pad = true),
        b("PAD_L1", "Left bumper", "L1", KeyEvent.KEYCODE_BUTTON_L1, pad = true),
        b("PAD_R1", "Right bumper", "R1", KeyEvent.KEYCODE_BUTTON_R1, pad = true),
        b("PAD_L2", "Left trigger", "L2", KeyEvent.KEYCODE_BUTTON_L2, pad = true),
        b("PAD_R2", "Right trigger", "R2", KeyEvent.KEYCODE_BUTTON_R2, pad = true),
        b("PAD_START", "Start", "START", KeyEvent.KEYCODE_BUTTON_START, pad = true),
        b("PAD_SELECT", "Select", "SEL", KeyEvent.KEYCODE_BUTTON_SELECT, pad = true),
        b("PAD_L3", "Left stick click", "L3", KeyEvent.KEYCODE_BUTTON_THUMBL, pad = true),
        b("PAD_R3", "Right stick click", "R3", KeyEvent.KEYCODE_BUTTON_THUMBR, pad = true)
    )

    /** Buttons no screen uses today: offered on every screen, and the only ones "Everywhere" takes. */
    val SPARE: Set<String> = setOf(
        "RED", "GREEN", "YELLOW", "BLUE", "GUIDE", "RECORD",
        "PAD_Y", "PAD_X", "PAD_L1", "PAD_R1", "PAD_L2", "PAD_R2", "PAD_START", "PAD_SELECT", "PAD_L3", "PAD_R3"
    )

    /**
     * Never offered and never learned, whatever a remote sends: Android or the TV owns them
     * (Home, Power, volume, the assistant), or they are how you move around.
     */
    val NEVER: Set<Int> = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_TV_INPUT,
        KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_STB_POWER, KeyEvent.KEYCODE_AVR_POWER, KeyEvent.KEYCODE_NOTIFICATION,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_UNKNOWN
    )

    private val byCode: Map<Int, RemoteButton> = buildMap { BUTTONS.forEach { btn -> btn.codes.forEach { put(it, btn) } } }
    private val byId: Map<String, RemoteButton> = BUTTONS.associateBy { it.id }
    fun button(id: String): RemoteButton? = byId[id]
    fun buttonForCode(code: Int): RemoteButton? = byCode[code]

    // ------------------------------------------------------------------ actions

    /** Go somewhere. These work from any screen. */
    val GO: List<RemoteAction> = listOf(
        RemoteAction("go.live_tv", "Live TV guide", "Go to", goes = true),
        RemoteAction("go.home", "Home", "Go to", goes = true),
        RemoteAction("go.sports", "Sports", "Go to", goes = true),
        RemoteAction("go.on_demand", "On Demand", "Go to", goes = true),
        RemoteAction("go.search", "Search", "Go to", goes = true),
        RemoteAction("go.recordings", "Recordings", "Go to", goes = true),
        RemoteAction("go.calendar", "Calendar", "Go to", goes = true),
        RemoteAction("go.multiview", "Multiview", "Go to", goes = true),
        RemoteAction("go.settings", "Settings", "Go to", goes = true)
    )

    private val LIVE_COMMON = listOf(
        RemoteAction("live.options", "Options", "Live TV"),
        RemoteAction("live.info_bar", "Info bar", "Live TV"),
        RemoteAction("live.pause", "Pause / resume", "Playback"),
        RemoteAction("live.audio", "Audio", "Audio & subtitles"),
        RemoteAction("live.subs_toggle", "Subtitles on / off", "Audio & subtitles"),
        RemoteAction("live.subs_page", "Subtitles", "Audio & subtitles"),
        RemoteAction("live.size", "Screen size", "Picture"),
        RemoteAction("live.stream_info", "Stream info", "Picture"),
        RemoteAction("live.sleep", "Sleep timer", "Other")
    )

    val EVERYWHERE = RemoteScreen(
        "everywhere", "Everywhere", "Shortcut buttons that work on any screen",
        own = emptyList(), actions = emptyList()
    )

    val GUIDE = RemoteScreen(
        "guide", "Live TV Guide", "The guide and its channel list",
        own = listOf(
            RemoteSlot("MENU", std = "guide.channel_menu"),
            RemoteSlot("INFO", std = "guide.channel_menu"),
            RemoteSlot("PLAY_PAUSE", std = "guide.fullscreen"),
            RemoteSlot("SEARCH", std = "guide.search"),
            RemoteSlot("CH_UP", stdText = "Page up"),
            RemoteSlot("CH_DOWN", stdText = "Page down"),
            RemoteSlot("REWIND", stdText = "4 shows back"),
            RemoteSlot("FAST_FORWARD", stdText = "4 shows forward"),
            RemoteSlot("LAST", stdText = "Previous channel"),
            RemoteSlot("BACK", stdText = "Back to now, then the groups, then the side menu", goOnly = true)
        ),
        actions = listOf(
            RemoteAction("guide.fullscreen", "Full screen on what's playing", "Live TV", goes = true),
            RemoteAction("guide.channel_menu", "Channel menu", "Live TV"),
            RemoteAction("guide.groups", "Groups", "Live TV", goes = true),
            RemoteAction("guide.favorites", "Favorites group", "Live TV"),
            RemoteAction("guide.play", "Watch the highlighted channel full screen", "Live TV"),
            RemoteAction("guide.favorite_toggle", "Favorite the highlighted channel", "Live TV"),
            RemoteAction("guide.multiview_add", "Add the highlighted channel to Multiview", "Live TV"),
            RemoteAction("guide.options", "Guide options (⚙)", "Live TV"),
            RemoteAction("guide.now", "Back to what's on now", "Live TV"),
            RemoteAction("guide.search", "Search channels", "Live TV"),
            RemoteAction("guide.previous_channel", "Previous channel", "Channels"),
            RemoteAction("guide.page_up", "Page up", "Channels", repeats = true),
            RemoteAction("guide.page_down", "Page down", "Channels", repeats = true)
        )
    )

    val FULL = RemoteScreen(
        "full", "Live TV Full Screen", "Watching a channel",
        own = listOf(
            RemoteSlot("MENU", std = "live.options"),
            RemoteSlot("INFO", std = "live.info_bar"),
            RemoteSlot("PLAY_PAUSE", std = "live.pause"),
            RemoteSlot("CH_UP", stdText = "Next channel"),
            RemoteSlot("CH_DOWN", stdText = "Previous channel in the list"),
            RemoteSlot("REWIND", stdText = "30 seconds back (Rewind Anytime)"),
            RemoteSlot("FAST_FORWARD", stdText = "30 seconds forward (Rewind Anytime)"),
            RemoteSlot("LAST", stdText = "The channel you watched before"),
            RemoteSlot("CAPTIONS"),
            RemoteSlot("SEARCH"),
            RemoteSlot("OK", std = "live.panel", stdHold = "live.options", double = false),
            RemoteSlot("UP", stdText = "Next channel"),
            RemoteSlot("DOWN", stdText = "Previous channel in the list"),
            RemoteSlot("LEFT", stdText = "Channel list (overlay mode) or the groups"),
            RemoteSlot("RIGHT", stdText = "Info bar · twice: backups · hold: the channel before"),
            RemoteSlot("BACK", stdText = "Close the info bar, then back to the guide", goOnly = true)
        ),
        actions = LIVE_COMMON + listOf(
            RemoteAction("live.panel", "Control panel", "Live TV"),
            RemoteAction("live.guide", "Guide", "Live TV", goes = true),
            RemoteAction("live.channel_list", "Channel list over the picture", "Live TV", goes = true),
            RemoteAction("live.groups", "Groups", "Live TV", goes = true),
            RemoteAction("live.next", "Next channel", "Channels", repeats = true),
            RemoteAction("live.prev", "Previous channel in the list", "Channels", repeats = true),
            RemoteAction("live.previous_channel", "The channel you watched before", "Channels", goes = true),
            RemoteAction("live.backups", "Backup streams", "Live TV"),
            RemoteAction("live.record", "Record / stop recording", "Live TV"),
            RemoteAction("live.favorite", "Favorite this channel", "Live TV"),
            RemoteAction("live.multiview_add", "Add to Multiview", "Live TV"),
            RemoteAction("live.multiview_open", "Open Multiview with this channel", "Live TV"),
            RemoteAction("live.reload", "Reload stream", "Other")
        )
    )

    val CATCHUP = RemoteScreen(
        "catchup", "Catch-up & Recordings", "Replays and recorded shows",
        own = listOf(
            RemoteSlot("MENU", std = "live.options"),
            RemoteSlot("INFO", std = "live.info_bar"),
            RemoteSlot("PLAY_PAUSE", std = "live.pause"),
            RemoteSlot("REWIND", stdText = "10 seconds back (held: faster)"),
            RemoteSlot("FAST_FORWARD", stdText = "10 seconds forward (held: faster)"),
            RemoteSlot("CH_UP", stdText = "Next channel (catch-up) · nothing in a recording"),
            RemoteSlot("CH_DOWN", stdText = "Previous channel (catch-up) · nothing in a recording"),
            RemoteSlot("CAPTIONS")
        ),
        actions = LIVE_COMMON + listOf(
            RemoteAction("catchup.back", "Skip back", "Playback", repeats = true),
            RemoteAction("catchup.forward", "Skip forward", "Playback", repeats = true),
            RemoteAction("catchup.live", "Back to live TV", "Playback")
        )
    )

    val MULTIVIEW = RemoteScreen(
        "multiview", "Multiview", "Several channels at once",
        own = listOf(RemoteSlot("MENU", std = "mv.edit"), RemoteSlot("PLAY_PAUSE"), RemoteSlot("INFO")),
        actions = listOf(
            RemoteAction("mv.edit", "Change the highlighted pane", "Multiview"),
            RemoteAction("mv.fullscreen", "Full screen on the pane / all panes", "Multiview"),
            RemoteAction("mv.layout", "Pick a layout", "Multiview")
        )
    )

    val SPORTS = RemoteScreen(
        "sports", "Sports", "Games, a game's page and tables",
        own = listOf(
            RemoteSlot("MENU"), RemoteSlot("PLAY_PAUSE"), RemoteSlot("INFO"),
            RemoteSlot("BACK", stdText = "The side menu · hold: full screen on the game", goOnly = true)
        ),
        actions = listOf(RemoteAction("sports.fullscreen", "Full screen on the game in the video window", "Sports", goes = true))
    )

    val PLAYER = RemoteScreen(
        "player", "Movies & Series Player", "Add-on streams and On Demand",
        own = listOf(
            RemoteSlot("MENU"),
            RemoteSlot("PLAY_PAUSE", std = "vod.play_pause"),
            RemoteSlot("INFO", stdText = "Stream info"),
            RemoteSlot("REWIND", stdText = "Skip back (held: faster)"),
            RemoteSlot("FAST_FORWARD", stdText = "Skip forward (held: faster)"),
            RemoteSlot("CH_UP", stdText = "Next chapter"),
            RemoteSlot("CH_DOWN", stdText = "Previous chapter"),
            RemoteSlot("UP", stdText = "Show the controls"),
            RemoteSlot("DOWN", stdText = "Show the controls")
        ),
        actions = listOf(
            RemoteAction("vod.play_pause", "Play / pause", "Playback"),
            RemoteAction("vod.controls", "Show the controls", "Playback"),
            RemoteAction("vod.seek_back", "Skip back 10 seconds", "Playback", repeats = true),
            RemoteAction("vod.seek_forward", "Skip forward 10 seconds", "Playback", repeats = true),
            RemoteAction("vod.chapter_next", "Next chapter", "Playback"),
            RemoteAction("vod.chapter_prev", "Previous chapter", "Playback"),
            RemoteAction("vod.next_episode", "Next episode", "Playback"),
            RemoteAction("vod.skip_intro", "Skip intro", "Playback"),
            RemoteAction("vod.speed", "Playback speed", "Playback"),
            RemoteAction("vod.subtitles", "Subtitles", "Audio & subtitles"),
            RemoteAction("vod.audio", "Audio", "Audio & subtitles"),
            RemoteAction("vod.subtitle_timing", "Subtitle timing", "Audio & subtitles"),
            RemoteAction("vod.episodes", "Episodes", "Panels"),
            RemoteAction("vod.sources", "Sources", "Panels"),
            RemoteAction("vod.chapters", "Chapters", "Panels"),
            RemoteAction("vod.more", "More menu", "Panels"),
            RemoteAction("vod.aspect", "Screen size", "Picture"),
            RemoteAction("vod.stream_info", "Stream info", "Picture")
        )
    )

    val BROWSE = RemoteScreen(
        "browse", "Browsing", "Home, On Demand, Library and Search",
        own = listOf(
            RemoteSlot("MENU", stdText = "Options for the highlighted poster"),
            RemoteSlot("PLAY_PAUSE"), RemoteSlot("INFO")
        ),
        actions = emptyList()
    )

    val SCREENS: List<RemoteScreen> = listOf(EVERYWHERE, GUIDE, FULL, CATCHUP, MULTIVIEW, SPORTS, PLAYER, BROWSE)
    fun screen(id: String): RemoteScreen? = SCREENS.firstOrNull { it.id == id }

    /** A controller's extra buttons do nothing today; this gives them sensible jobs in one go. */
    val PAD_SUGGESTED: Map<String, Map<String, String>> = mapOf(
        "full" to mapOf(
            "PAD_L1" to "live.prev", "PAD_R1" to "live.next", "PAD_Y" to "live.options", "PAD_X" to "live.guide",
            "PAD_START" to "live.pause", "PAD_SELECT" to "live.info_bar"
        ),
        "catchup" to mapOf(
            "PAD_L2" to "catchup.back", "PAD_R2" to "catchup.forward", "PAD_Y" to "live.options",
            "PAD_START" to "live.pause", "PAD_SELECT" to "live.info_bar"
        ),
        "guide" to mapOf(
            "PAD_L1" to "guide.page_up", "PAD_R1" to "guide.page_down", "PAD_Y" to "guide.channel_menu",
            "PAD_X" to "guide.fullscreen", "PAD_START" to "guide.fullscreen"
        ),
        "player" to mapOf(
            "PAD_L2" to "vod.seek_back", "PAD_R2" to "vod.seek_forward", "PAD_Y" to "vod.more",
            "PAD_X" to "vod.subtitles", "PAD_START" to "vod.play_pause", "PAD_SELECT" to "vod.stream_info",
            "PAD_L1" to "vod.chapter_prev", "PAD_R1" to "vod.chapter_next"
        )
    )
}
