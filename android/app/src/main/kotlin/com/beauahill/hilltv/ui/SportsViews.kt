package com.beauahill.hilltv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauahill.hilltv.core.GameChannelMatch
import com.beauahill.hilltv.core.GameChannelMatch.Strength
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.SportsGame
import com.beauahill.hilltv.core.SportsTeam

// Views for games in the Sports pill of Live TV. Scores and logos come from
// `ScoreboardStore`; which channel carries a game comes from `GameMatcher`.

/** A game and the channels offered for it, for the "where to watch" list. */
class GamePick(val game: SportsGame, val matches: List<GameChannelMatch>)

/**
 * Team logo loaded from the scores feed, on a round badge. Falls back to
 * the abbreviation while it loads or if the link is dead.
 */
@Composable
fun TeamLogo(team: SportsTeam, size: Dp = 22.dp) {
    Box(Modifier.size(size).background(Theme.controlRaised, CircleShape).padding(size * 0.12f)) {
        RemoteImage(team.logoURL, Modifier.fillMaxSize()) {
            Txt(
                team.abbreviation ?: team.shortName.take(3).uppercase(),
                style = TextStyle(fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold),
                color = Theme.text,
                maxLines = 1,
            )
        }
    }
}

/** Red dot and clock for a live game, start time before it, "Final" after. */
@Composable
fun GameStatusLabel(game: SportsGame, color: Color = Color.Unspecified) {
    val text = when (game.state) {
        SportsGame.State.SCHEDULED -> gameStartLabel(game.start)
        SportsGame.State.LIVE -> game.statusText.ifEmpty { "Live" }
        SportsGame.State.FINISHED -> game.statusText.ifEmpty { "Final" }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (game.state == SportsGame.State.LIVE) Box(Modifier.size(5.dp).background(Theme.live, CircleShape))
        Txt(text, style = Type.small, color = color, maxLines = 1, weight = FontWeight.SemiBold, tabular = true)
    }
}

/**
 * One game in the Live TV list: both teams with logos and scores, the game
 * clock, and the channel it was matched to.
 */
@Composable
fun RowScope.GameRow(game: SportsGame, matches: List<GameChannelMatch>, channelsLoaded: Boolean) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TeamLine(game, game.away)
        TeamLine(game, game.home)
    }
    Column(
        Modifier.padding(start = 12.dp).width(130.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GameStatusLabel(game)
        Txt(gameChannelText(matches, channelsLoaded), style = Type.small, color = secondaryColor(), maxLines = 1)
    }
}

@Composable
private fun TeamLine(game: SportsGame, team: SportsTeam) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team)
        // The score always gets its full width. A long team name gives way instead.
        Txt(team.shortName, Modifier.weight(1f), Type.meta, maxLines = 1, weight = FontWeight.SemiBold)
        if (game.state != SportsGame.State.SCHEDULED && team.score != null) {
            Txt(team.score ?: "", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, tabular = true)
        }
    }
}

/**
 * Says how sure the match is: a named channel when both teams were
 * found, "Maybe" when only the network matched.
 */
private fun gameChannelText(matches: List<GameChannelMatch>, channelsLoaded: Boolean): String {
    val sure = matches.filter { it.strength == Strength.TEAMS }
    sure.firstOrNull()?.let { first ->
        return if (sure.size > 1) "${first.stream.name} +${sure.size - 1}" else first.stream.name
    }
    matches.firstOrNull()?.let { first ->
        return if (matches.size == 1) "Maybe ${first.stream.name}" else "Maybe on ${matches.size} channels"
    }
    return if (channelsLoaded) "No matching channel" else "Finding channels"
}

/**
 * Right-hand side of Live TV while a game has focus: a scoreboard where the
 * channel still would be, then where to watch it.
 */
@Composable
fun GameDetailPane(game: SportsGame, matches: List<GameChannelMatch>, channelsLoaded: Boolean) {
    Column(Modifier.fillMaxSize().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .heightIn(max = 220.dp)
                .widescreen()
                .clip(RoundedCornerShape(Theme.radius))
                .background(Theme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScoreboardSide(game.away)
                // The middle takes the width its scores need; the team names on either side shrink to make room.
                ScoreboardCenter(game)
                ScoreboardSide(game.home)
            }
            LiveBadge(Modifier.align(Alignment.TopStart).padding(10.dp), game.league.name, dot = false)
        }

        Txt("${game.away.displayName} at ${game.home.displayName}", style = Type.subtitle, color = Theme.text, maxLines = 1)

        val sure = matches.filter { it.strength == Strength.TEAMS }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Eyebrow("Where to watch")
            if (matches.isEmpty()) {
                Txt(
                    if (channelsLoaded) "No matching channel in your Sports categories." else "Finding channels",
                    style = Type.meta, color = Theme.muted,
                )
            } else {
                for (match in sure.ifEmpty { matches }.take(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(match.stream.iconURL, width = 36.dp, height = 24.dp)
                        Txt(match.stream.name, style = Type.meta, color = Theme.text, maxLines = 1)
                    }
                }
                if (sure.isEmpty()) {
                    Txt("Matched by network name only. Press Select to choose a channel.", style = Type.small, color = Theme.muted)
                }
            }
        }

        Txt("Scores come from ESPN and can run ahead of a delayed stream.", style = Type.small, color = Theme.muted)
    }
}

@Composable
private fun RowScope.ScoreboardSide(team: SportsTeam) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TeamLogo(team, 65.dp)
        Txt(team.shortName, style = Type.body, color = Theme.text, maxLines = 1, weight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ScoreboardCenter(game: SportsGame) {
    if (game.state == SportsGame.State.SCHEDULED) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("Starts", style = Type.small, color = Theme.muted)
            Txt(gameStartLabel(game.start), style = Type.heading, color = Theme.text, maxLines = 1)
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                // Sized so two three-digit basketball scores fit between the logos.
                val scoreStyle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = Type.condensedFamily)
                Txt(game.away.score ?: "0", style = scoreStyle, color = Theme.text, maxLines = 1, tabular = true)
                Txt(game.home.score ?: "0", style = scoreStyle, color = Theme.text, maxLines = 1, tabular = true)
            }
            GameStatusLabel(game, Theme.text)
        }
    }
}

/**
 * Full-screen list of the channels a game may be on. Shown instead of
 * tuning straight in whenever the match isn't a single sure one.
 */
@Composable
fun GameChannelPicker(pick: GamePick, onPick: (LiveStream) -> Unit) {
    val first = rememberInitialFocus()
    CoverPage("Where to watch", "${pick.game.away.shortName} at ${pick.game.home.shortName}") {
        LazyColumn(
            Modifier.widthIn(max = 600.dp),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(pick.matches, key = { _, match -> match.id }) { index, match ->
                TVRow(
                    onClick = { onPick(match.stream) },
                    modifier = if (index == 0) Modifier.initialFocus(first) else Modifier,
                    minHeight = Theme.listRowHeight,
                ) {
                    ChannelLogo(match.stream.iconURL)
                    Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(match.stream.name, style = Type.meta, maxLines = 1, weight = FontWeight.SemiBold)
                        Txt(
                            if (match.strength == Strength.TEAMS) "Names both teams" else "Carries the network, may be a different game",
                            style = Type.small, color = secondaryColor(), maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
