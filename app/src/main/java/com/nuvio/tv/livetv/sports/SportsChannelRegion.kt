package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * "Channels from": which region's (or language's) channels a game's list shows.
 *
 * Worked out from what's already matched to a game (a dozen channels at most), never by
 * searching the guide again, so changing it is instant and costs no loading time. Saved in
 * [SportsSettings.channelRegion]: "" = all, "UK"/"US"/"IE"/"CA"/"AU", "EN" = any of those, or
 * "lang:es" and the like for a language.
 */
internal object ChannelRegions {
    const val ENGLISH = "EN"
    private val ENGLISH_REGIONS = setOf("UK", "US", "IE", "CA", "AU")

    private val LANGUAGES = listOf(
        "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ar" to "Arabic", "ru" to "Russian"
    )

    /** Every choice, for Sports settings (the game page offers only the ones its channels have). */
    val all: List<Pair<String, String>> = listOf(
        "" to "All regions",
        ENGLISH to "English (UK, US, Ireland, Canada, Australia)",
        "UK" to "United Kingdom",
        "US" to "United States",
        "IE" to "Ireland",
        "CA" to "Canada",
        "AU" to "Australia & New Zealand"
    ) + LANGUAGES.map { (code, name) -> "lang:$code" to name }

    fun name(code: String): String = all.firstOrNull { it.first == code }?.second ?: code

    /** The short form, for the button on the game page. */
    fun shortName(code: String): String = when (code) {
        "" -> "All regions"
        ENGLISH -> "English"
        "UK" -> "UK"
        "US" -> "USA"
        "AU" -> "Australia"
        else -> name(code)
    }

    /** Does a channel from [channelCode] (see [SportsMatcher.channelRegionCode]) belong to [choice]? */
    fun includes(channelCode: String?, choice: String): Boolean = when {
        choice.isBlank() -> true
        channelCode == null -> false
        choice == ENGLISH -> channelCode in ENGLISH_REGIONS
        else -> channelCode == choice
    }

    fun includes(ch: LiveChannel, choice: String): Boolean =
        choice.isBlank() || includes(SportsMatcher.channelRegionCode(ch), choice)

    /**
     * [list] with the chosen region's channels moved up (your own picks stay on top; the order
     * is otherwise kept). Used for every game, so reminders, Watch now and the home rows pick a
     * channel from your region too.
     */
    fun regionFirst(list: List<ChannelMatch>, choice: String): List<ChannelMatch> {
        if (choice.isBlank() || list.size < 2) return list
        return list.sortedWith(
            compareByDescending<ChannelMatch> { if (it.score >= 150) 1 else 0 }
                .thenByDescending { if (includes(it.channel, choice)) 1 else 0 }
        )
    }

    /** The choices a game's channels offer, with how many of its channels each one has. */
    fun choicesFor(codes: Collection<String?>, current: String): List<Pair<String, Int>> {
        val counts = codes.filterNotNull().groupingBy { it }.eachCount()
        val out = ArrayList<Pair<String, Int>>()
        val english = codes.count { it != null && it in ENGLISH_REGIONS }
        // "English" is worth offering when it groups more than one region, or sets them apart from other languages.
        val englishRegions = counts.keys.count { it in ENGLISH_REGIONS }
        if (english > 0 && (englishRegions > 1 || counts.keys.any { it !in ENGLISH_REGIONS })) out += ENGLISH to english
        counts.entries.sortedByDescending { it.value }.forEach { (code, n) -> out += code to n }
        if (current.isNotBlank() && out.none { it.first == current }) out += current to 0
        return out
    }
}

/** The small "🌐 UK ▾" button beside WATCH ON YOUR CHANNELS. */
@Composable
internal fun RegionButton(choice: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    LiveText(
        "🌐  " + ChannelRegions.shortName(choice) + "  ▾",
        modifier = modifier
            .clip(shape)
            .background(if (choice.isNotBlank()) Color.White.copy(alpha = 0.14f) else SportsLook.Card)
            .border(
                if (focused) NuvioTheme.focusRing.border(2.dp)
                else androidx.compose.foundation.BorderStroke(1.dp, if (choice.isNotBlank()) Color.White.copy(alpha = 0.35f) else SportsLook.Border),
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp),
        color = Color.White,
        size = 11.sp, weight = FontWeight.ExtraBold
    )
}

/** Pick which region's channels the game page shows (remembered for every game). */
@Composable
internal fun RegionDialog(
    codes: Collection<String?>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val choices = remember(codes, current) { ChannelRegions.choicesFor(codes, current) }
    val selected = remember { FocusRequester() }
    fun count(n: Int) = if (n == 1) "1 channel" else "$n channels"
    SportsDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText("Channels from", color = Color.White, size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "Shows only that region's channels for every game (handy for commentary in your language). " +
                "Reminders and Watch now use them first too. The others are one click away.",
            color = SportsLook.Dim, size = 12.sp, maxLines = 3
        )
        Spacer(Modifier.height(10.dp))
        SportsMenuRow(
            (if (current.isBlank()) "✓  " else "     ") + "All regions",
            if (current.isBlank()) Modifier.focusRequester(selected) else Modifier,
            detail = count(codes.size)
        ) { onPick("") }
        choices.forEach { (code, n) ->
            SportsMenuRow(
                (if (code == current) "✓  " else "     ") + ChannelRegions.name(code),
                if (code == current) Modifier.focusRequester(selected) else Modifier,
                detail = if (n == 0) "None for this game" else count(n)
            ) { onPick(code) }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { selected.requestFocus() } }
}
