package com.beauahill.hilltv.core

import java.time.Instant

/** A channel that probably carries a game, and how sure the match is. */
data class GameChannelMatch(val stream: LiveStream, val strength: Strength) {
    enum class Strength {
        /**
         * Only the network name matched ("ESPN"). Many channels can share
         * one, and regional games differ, so never tune on this alone.
         */
        NETWORK,

        /** Both teams are named in the channel name or its guide listing. */
        TEAMS,
    }

    val id: Int get() = stream.id
}

/**
 * Ties games from the scores feed to the provider's channels. The provider
 * knows nothing about teams, so this works from text: channel names
 * ("NFL 03: Colts vs Texans"), guide titles, and network names.
 */
object GameMatcher {
    /**
     * Channels that may carry `game`, strongest first, provider order kept
     * within each strength. `schedules` is whatever guide data is already
     * loaded; channels without any are still matched by name.
     */
    fun matches(
        game: SportsGame,
        channels: List<LiveStream>,
        schedules: Map<Int, EPGSchedule> = emptyMap(),
    ): List<GameChannelMatch> = matches(game, ChannelIndex(channels, schedules))

    /**
     * The same for many games at once, keyed by game ID. Channel names and
     * guide titles are folded once instead of once per game.
     *
     * Stops early when `isCancelled` turns true, with only the games done so
     * far. A caller that cancels must not use the result.
     */
    fun matchesForAll(
        games: List<SportsGame>,
        channels: List<LiveStream>,
        schedules: Map<Int, EPGSchedule> = emptyMap(),
        isCancelled: () -> Boolean = { false },
    ): Map<String, List<GameChannelMatch>> {
        val index = ChannelIndex(channels, schedules)
        val result = LinkedHashMap<String, List<GameChannelMatch>>()
        for (game in games) {
            if (game.id in result) continue
            if (isCancelled()) break
            result[game.id] = matches(game, index)
        }
        return result
    }

    /** A channel with its searchable text prepared. */
    internal class IndexedChannel(val stream: LiveStream, val name: String, val listings: List<Listing>) {
        class Listing(val start: Instant, val end: Instant, val text: String)
    }

    /**
     * Every channel with its text folded, plus a word index over them.
     *
     * A sports section can hold thousands of channels and a busy day a
     * hundred games or more. Checking every game against every channel is
     * then hundreds of thousands of text searches. The word index narrows
     * each game to the few channels that mention one of its teams or
     * networks at all.
     */
    internal class ChannelIndex(streams: List<LiveStream>, schedules: Map<Int, EPGSchedule>) {
        /** Provider order. Repeated stream IDs are dropped. */
        val channels: List<IndexedChannel>

        /** For each word: where in `channels` it appears, in a name or in guide text. Positions ascend. */
        private val positions: Map<String, MutableList<Int>>

        init {
            val seen = HashSet<Int>()
            val channels = ArrayList<IndexedChannel>()
            val positions = HashMap<String, MutableList<Int>>()
            for (stream in streams) {
                if (!seen.add(stream.id)) continue
                val listings = (schedules[stream.id]?.entries ?: emptyList()).map { entry ->
                    IndexedChannel.Listing(entry.start, entry.end, fold(entry.title + " " + (entry.summary ?: "")))
                }
                val channel = IndexedChannel(stream, fold(stream.name), listings)
                val words = HashSet<String>()
                words.addAll(channel.name.split(' '))
                for (listing in listings) words.addAll(listing.text.split(' '))
                for (word in words) {
                    if (word.isEmpty()) continue
                    positions.getOrPut(word) { ArrayList() }.add(channels.size)
                }
                channels.add(channel)
            }
            this.channels = channels
            this.positions = positions
        }

        /**
         * Channels that have the first word of at least one phrase, in
         * provider order. A channel can't contain a phrase without it.
         */
        fun candidates(phrases: List<String>): List<IndexedChannel> {
            val found = HashSet<Int>()
            for (phrase in phrases) {
                val first = phrase.split(' ').firstOrNull { it.isNotEmpty() } ?: continue
                positions[first]?.let(found::addAll)
            }
            return found.sorted().map { channels[it] }
        }
    }

    /** What to look for in a channel's text for one game. */
    internal class GameTerms(game: SportsGame) {
        val home = TeamPhrases(game.home)
        val away = TeamPhrases(game.away)
        val networks: List<String> = game.networks.map { fold(it) }.filter { it.isNotEmpty() }

        // Listings that overlap the game, with some slack for pregame and overtime.
        val windowStart: Instant = game.start.minusSeconds(30 * 60)
        val windowEnd: Instant = game.start.plusSeconds(4 * 3600)

        /**
         * A match needs one of these somewhere: a team named outright (a
         * city alone is never enough) or the network's name.
         */
        val required: List<String> get() = home.names + away.names + networks
    }

    internal fun matches(game: SportsGame, index: ChannelIndex): List<GameChannelMatch> {
        val terms = GameTerms(game)
        return matches(terms, index.candidates(terms.required))
    }

    /**
     * Checks every channel. `matches(game, index)` must give the same answer;
     * the tests hold it to this.
     */
    internal fun sweep(game: SportsGame, index: ChannelIndex): List<GameChannelMatch> =
        matches(GameTerms(game), index.channels)

    private fun matches(terms: GameTerms, channels: List<IndexedChannel>): List<GameChannelMatch> {
        val strong = ArrayList<GameChannelMatch>()
        val weak = ArrayList<GameChannelMatch>()

        for (channel in channels) {
            val inGuide = channel.listings.any { listing ->
                listing.end > terms.windowStart && listing.start < terms.windowEnd &&
                    namesBothOf(terms.home, terms.away, listing.text)
            }
            if (inGuide || namesBothOf(terms.home, terms.away, channel.name)) {
                strong.add(GameChannelMatch(channel.stream, GameChannelMatch.Strength.TEAMS))
            } else if (terms.networks.any { isNetwork(it, channel.name) }) {
                weak.add(GameChannelMatch(channel.stream, GameChannelMatch.Strength.NETWORK))
            }
        }
        return strong + weak
    }

    // MARK: Text matching

    internal fun fold(string: String): String = SearchIndex.fold(string)

    /**
     * Ways a listing might name a team, folded. Abbreviations are left out:
     * "NO", "IND" and "ATL" are too easily other words.
     */
    internal class TeamPhrases(team: SportsTeam) {
        /** Full name, short name and nickname: "copper basin kings", "kings". */
        val names: List<String>

        /**
         * The city or state alone, when it isn't also the short name.
         * Several teams can share one, across leagues too.
         */
        val places: List<String>

        init {
            val names = ArrayList<String>()
            for (candidate in listOf(team.displayName, team.shortName, team.nickname ?: "")) {
                val folded = fold(candidate)
                // One or two characters match far too much.
                if (folded.length >= 3 && folded !in names) names.add(folded)
            }
            this.names = names
            val place = fold(team.location ?: "")
            places = if (place.length >= 3 && place !in names) listOf(place) else emptyList()
        }
    }

    /**
     * True when `text` names both teams. A city alone is accepted for one
     * side but not both: "Los Angeles at Boston" could be any sport.
     */
    internal fun namesBothOf(home: TeamPhrases, away: TeamPhrases, text: String): Boolean {
        val homeNamed = mentions(text, home.names)
        val awayNamed = mentions(text, away.names)
        if (!homeNamed && !awayNamed) return false
        return (homeNamed || mentions(text, home.places)) && (awayNamed || mentions(text, away.places))
    }

    /** True when `text` contains one of the phrases as whole words. */
    internal fun mentions(text: String, phrases: List<String>): Boolean {
        val padded = " $text "
        return phrases.any { padded.contains(" $it ") }
    }

    /**
     * True when the channel name is the network plus nothing but noise
     * ("US: ESPN HD"). Anything else after it means a different channel:
     * "ESPN 2", "ESPN News", "CBS Sports Network", a local affiliate.
     */
    internal fun isNetwork(network: String, channelName: String): Boolean {
        val networkWords = network.split(' ').filter { it.isNotEmpty() }
        val words = channelName.split(' ').filter { it.isNotEmpty() }
        if (networkWords.isEmpty() || words.size < networkWords.size) return false
        val start = (0..words.size - networkWords.size).firstOrNull { index ->
            words.subList(index, index + networkWords.size) == networkWords
        } ?: return false
        val rest = words.subList(0, start) + words.subList(start + networkWords.size, words.size)
        return rest.all { it in noise }
    }

    private val noise = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "h264", "1080", "1080p", "720", "720p", "60fps", "50fps",
        "us", "usa", "uk", "ca", "east", "west", "backup", "vip", "raw", "multi", "feed", "tv", "channel", "live",
    )
}
