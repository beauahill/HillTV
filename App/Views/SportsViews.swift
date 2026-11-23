import HillTVCore
import SwiftUI

// Views for games in the Sports pill of Live TV. Scores and logos come from
// `ScoreboardStore`; which channel carries a game comes from `GameMatcher`.

/// A game and the channels offered for it, for the "where to watch" list.
struct GamePick: Identifiable {
    let game: SportsGame
    let matches: [GameChannelMatch]

    var id: String { game.id }
}

/// "6:30 PM" today, "Sun 1:00 PM" on another day.
func gameStartLabel(_ date: Date) -> String {
    if Calendar.current.isDateInToday(date) {
        return date.shortTime
    }
    return date.formatted(.dateTime.weekday(.abbreviated).hour().minute())
}

/// Team logo loaded from the scores feed, on a round badge. Falls back to
/// the abbreviation while it loads or if the link is dead.
struct TeamLogo: View {
    let team: SportsTeam
    var size: CGFloat = 44

    var body: some View {
        AsyncImage(url: team.logoURL) { phase in
            if let image = phase.image {
                image.resizable().scaledToFit()
            } else {
                Text(team.abbreviation ?? String(team.shortName.prefix(3)).uppercased())
                    .font(.system(size: size * 0.3, weight: .bold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
        }
        .padding(size * 0.12)
        .frame(width: size, height: size)
        .background(Theme.controlRaised, in: Circle())
    }
}

/// Red dot and clock for a live game, start time before it, "Final" after.
struct GameStatusLabel: View {
    let game: SportsGame

    var body: some View {
        HStack(spacing: 8) {
            if game.state == .live {
                Circle()
                    .fill(Theme.live)
                    .frame(width: 10, height: 10)
            }
            Text(text)
                .font(.tvSmall.weight(.semibold).monospacedDigit())
                .lineLimit(1)
        }
    }

    private var text: String {
        switch game.state {
        case .scheduled: return gameStartLabel(game.start)
        case .live: return game.statusText.isEmpty ? "Live" : game.statusText
        case .finished: return game.statusText.isEmpty ? "Final" : game.statusText
        }
    }
}

/// One game in the Live TV list: both teams with logos and scores, the game
/// clock, and the channel it was matched to.
struct GameRow: View {
    let game: SportsGame
    let matches: [GameChannelMatch]
    /// False while the sports channels are still being fetched or matched.
    let channelsLoaded: Bool

    var body: some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 10) {
                teamLine(game.away)
                teamLine(game.home)
            }
            VStack(alignment: .trailing, spacing: 8) {
                GameStatusLabel(game: game)
                Text(channelText)
                    .font(.tvSmall)
                    .tvSecondary()
                    .lineLimit(1)
            }
            .frame(width: 260, alignment: .trailing)
        }
    }

    private func teamLine(_ team: SportsTeam) -> some View {
        HStack(spacing: 14) {
            TeamLogo(team: team, size: 44)
            Text(team.shortName)
                .font(.tvMeta.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            Spacer(minLength: 8)
            if game.state != .scheduled, let score = team.score {
                // The score always gets its full width. A long team name
                // gives way instead; otherwise the score collapses to "…".
                Text(score)
                    .font(.system(size: 30, weight: .bold).monospacedDigit())
                    .fixedSize()
                    .layoutPriority(1)
            }
        }
    }

    /// Says how sure the match is: a named channel when both teams were
    /// found, "Maybe" when only the network matched.
    private var channelText: String {
        let sure = matches.filter { $0.strength == .teams }
        if let first = sure.first {
            return sure.count > 1 ? "\(first.stream.name) +\(sure.count - 1)" : first.stream.name
        }
        if let first = matches.first {
            return matches.count == 1 ? "Maybe \(first.stream.name)" : "Maybe on \(matches.count) channels"
        }
        return channelsLoaded ? "No matching channel" : "Finding channels"
    }
}

/// Right-hand side of Live TV while a game has focus: a scoreboard where the
/// channel still would be, then where to watch it.
struct GameDetailPane: View {
    let game: SportsGame
    let matches: [GameChannelMatch]
    /// False while the sports channels are still being fetched or matched.
    let channelsLoaded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            scoreboard
                .frame(maxHeight: 440)

            Text("\(game.away.displayName) at \(game.home.displayName)")
                .font(.tvSubtitle)
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            whereToWatch

            Text("Scores come from ESPN and can run ahead of a delayed stream.")
                .font(.tvSmall)
                .foregroundStyle(Theme.muted)

            Spacer(minLength: 0)
        }
        .padding(.top, 12)
    }

    private var scoreboard: some View {
        ZStack {
            Theme.surface
            HStack(spacing: 20) {
                side(game.away)
                // The middle takes the width its scores need; the team
                // names on either side shrink to make room.
                center
                    .fixedSize()
                    .layoutPriority(1)
                side(game.home)
            }
            .padding(.horizontal, 24)
        }
        .aspectRatio(16.0 / 9.0, contentMode: .fit)
        .overlay(alignment: .topLeading) {
            Text(game.league.name)
                .font(.tvSmall.weight(.semibold))
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Theme.background.opacity(0.7), in: Capsule())
                .padding(20)
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
    }

    private func side(_ team: SportsTeam) -> some View {
        VStack(spacing: 14) {
            TeamLogo(team: team, size: 130)
            Text(team.shortName)
                .font(.tvBody.weight(.semibold))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var center: some View {
        if game.state == .scheduled {
            VStack(spacing: 8) {
                Text("Starts")
                    .font(.tvSmall)
                    .foregroundStyle(Theme.muted)
                Text(gameStartLabel(game.start))
                    .font(.tvHeading)
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
            }
        } else {
            VStack(spacing: 10) {
                HStack(spacing: 30) {
                    score(game.away)
                    score(game.home)
                }
                GameStatusLabel(game: game)
                    .foregroundStyle(Theme.text)
            }
        }
    }

    private func score(_ team: SportsTeam) -> some View {
        Text(team.score ?? "0")
            // Sized so two three-digit basketball scores fit between the logos.
            .font(.system(size: 88, weight: .bold).width(.condensed).monospacedDigit())
            .foregroundStyle(Theme.text)
            .lineLimit(1)
    }

    @ViewBuilder
    private var whereToWatch: some View {
        let sure = matches.filter { $0.strength == .teams }
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: "Where to watch")
            if matches.isEmpty {
                Text(channelsLoaded ? "No matching channel in your Sports categories." : "Finding channels")
                    .font(.tvMeta)
                    .foregroundStyle(Theme.muted)
            } else {
                ForEach((sure.isEmpty ? matches : sure).prefix(3)) { match in
                    HStack(spacing: 16) {
                        ChannelLogo(url: match.stream.iconURL, size: CGSize(width: 72, height: 48))
                        Text(match.stream.name)
                            .font(.tvMeta)
                            .foregroundStyle(Theme.text)
                            .lineLimit(1)
                    }
                }
                if sure.isEmpty {
                    Text("Matched by network name only. Press Select to choose a channel.")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                }
            }
        }
    }
}

/// Full-screen list of the channels a game may be on. Shown instead of
/// tuning straight in whenever the match isn't a single sure one.
struct GameChannelPicker: View {
    let pick: GamePick
    let onPick: (LiveStream) -> Void

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Where to watch")
                    Text("\(pick.game.away.shortName) at \(pick.game.home.shortName)")
                        .font(.tvTitle)
                        .foregroundStyle(Theme.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .padding(.leading, 36)

                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(pick.matches) { match in
                            Button {
                                onPick(match.stream)
                            } label: {
                                HStack(spacing: 20) {
                                    ChannelLogo(url: match.stream.iconURL)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(match.stream.name)
                                            .font(.tvMeta.weight(.semibold))
                                            .lineLimit(1)
                                        Text(match.strength == .teams ? "Names both teams" : "Carries the network, may be a different game")
                                            .font(.tvSmall)
                                            .tvSecondary()
                                            .lineLimit(1)
                                    }
                                    Spacer(minLength: 0)
                                }
                            }
                            .buttonStyle(TVRowButtonStyle(minHeight: Theme.listRowHeight))
                        }
                    }
                    .padding(12)
                }
                .frame(maxWidth: 1200, alignment: .leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .preferredColorScheme(.dark)
    }
}
