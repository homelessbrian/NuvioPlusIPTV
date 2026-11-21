package com.nuvio.tv.livetv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.livetv.sports.TeamInfo
import com.nuvio.tv.livetv.sports.TeamLogo
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The text part of Home's hero for a highlighted Live TV or Sports card, in place of the
 * movie layout. Games: the league, both teams on their own lines with a small logo (never the
 * score), LIVE and the clock or when it starts, a progress bar, and the channels it's on.
 * Channels: the channel, the show, LIVE with its times and how long is left, a progress bar,
 * the description and what's on next. Colours follow the app's theme.
 */
@Composable
internal fun LiveHeroTitle(hero: LiveHero, modifier: Modifier = Modifier) {
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Top line: the league, or the channel.
            if (hero.teams.isEmpty() && hero.channelName != null) {
                ChannelLine(hero)
            } else {
                hero.overline?.let {
                    Text(
                        it.uppercase(),
                        style = type.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
                        color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // The matchup, or the show.
            if (hero.teams.size == 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hero.teams.forEach { TeamLine(it) }
                }
            } else {
                Text(
                    hero.title,
                    style = type.headlineLarge,
                    color = colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }

            // LIVE / when it starts, and the times.
            if (hero.live || hero.tag != null || hero.status.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hero.live) {
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(colors.BackgroundElevated)
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.Error))
                            Text(stringResource(R.string.home_hero_live), style = type.labelMedium.copy(fontWeight = FontWeight.Bold), color = colors.TextPrimary)
                        }
                    } else hero.tag?.let {
                        Text(
                            it.uppercase(),
                            style = type.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.TextPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.BackgroundElevated)
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                    hero.status.forEachIndexed { i, s ->
                        if (i > 0) Text("·", style = type.bodyLarge, color = colors.TextTertiary)
                        Text(s, style = type.bodyLarge, color = colors.TextSecondary, maxLines = 1)
                    }
                }
            }

            hero.progress?.let { p ->
                Box(Modifier.width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.TextPrimary.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(colors.Secondary))
                }
            }

            hero.description?.let {
                Text(
                    it, style = type.bodyMedium, color = colors.TextSecondary,
                    maxLines = if (hero.teams.isEmpty()) 3 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 560.dp)
                )
            }

            if (hero.watchOn.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_hero_watch_on), style = type.bodyMedium, color = colors.TextSecondary)
                    hero.watchOn.forEach { name ->
                        Text(
                            name, style = type.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = colors.TextPrimary, maxLines = 1,
                            modifier = Modifier.border(1.dp, colors.TextPrimary.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (hero.nextTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_hero_next), style = type.labelMedium.copy(fontWeight = FontWeight.Bold), color = colors.TextTertiary)
                    hero.nextTime?.let { Text(it, style = type.bodyMedium, color = colors.TextSecondary) }
                    Text(hero.nextTitle, style = type.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The channel above the show: its logo (its name when there's none or it won't load), number and name. */
@Composable
private fun ChannelLine(hero: LiveHero) {
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        var failed by remember(hero.channelLogo) { mutableStateOf(false) }
        if (hero.channelLogo != null && !failed) {
            AsyncImage(
                model = hero.channelLogo, contentDescription = null, contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.height(36.dp).width(72.dp)
            )
        }
        hero.channelNumber?.let { Text(it, style = type.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.TextSecondary) }
        hero.channelName?.let { Text(it, style = type.titleSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** One team: a small logo, the team's place or short name, and its nickname. */
@Composable
private fun TeamLine(team: TeamInfo) {
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    val name = team.name.ifBlank { team.fullName }
    val nickname = team.fullName.takeIf { it.length > name.length && it.startsWith(name) }
        ?.removePrefix(name)?.trim()?.takeIf { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TeamLogo(team, 40.dp)
        Text(
            name, style = type.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 2.dp)
        )
        nickname?.let { Text(it, style = type.titleMedium, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
