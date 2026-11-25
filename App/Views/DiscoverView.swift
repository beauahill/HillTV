import HillTVCore
import Observation
import SwiftUI

/// Something the Discover page can show in its billboard.
enum BillboardItem: Hashable {
    case live(LiveStream)
    case resume(WatchEntry)
    case movie(VODStream)
    case series(SeriesItem)
}

/// Discover's own state. The rails themselves come from `HomeModel`, which
/// already loads and caches them, so the two pages share one set of requests.
@MainActor
@Observable
final class DiscoverModel {
    let client: XtreamClient
    /// Pushed detail pages. Empty means Discover is at its root.
    var path = NavigationPath()
    /// Movie details fetched for the billboard, by stream ID.
    private(set) var movieInfo: [Int: VODInfo] = [:]
    /// Movies whose details were asked for and didn't come.
    private var missing: Set<Int> = []

    init(client: XtreamClient) {
        self.client = client
    }

    /// Fetches a movie's plot and backdrop once. Called after focus has
    /// rested on the movie, so passing over a row doesn't ask for each one.
    func loadInfo(for movie: VODStream) async {
        guard movieInfo[movie.id] == nil, !missing.contains(movie.id) else { return }
        do {
            movieInfo[movie.id] = try await client.vodInfo(streamID: movie.id)
        } catch {
            // Leaving the movie cancels the request; that isn't "no details".
            if !Task.isCancelled { missing.insert(movie.id) }
        }
    }
}

/// Discover: a billboard on top that shows whatever has focus, live or on
/// demand, and rows underneath that mix both. Live channels first, then
/// continue watching, then movies and series.
///
/// Live channels have no picture here, only the provider's logo and the
/// guide's text: a picture would mean a connection per channel.
struct DiscoverView: View {
    @Bindable var model: DiscoverModel
    let catalog: HomeModel
    let epg: EPGStore

    /// A tile: the rail it is in and what it shows. The rail is part of it
    /// because one movie can sit in two rails.
    private struct Tile: Hashable {
        let rail: String
        let item: BillboardItem
    }

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?
    /// What the billboard shows: the last tile focus rested on.
    @State private var shown: BillboardItem?
    @FocusState private var focus: Tile?

    private static let posterWidth: CGFloat = 170

    /// Before anything has had focus, lead with the first thing on the page.
    private var billboardItem: BillboardItem? {
        if let shown { return shown }
        if let stream = app.favorites.channels.first { return .live(stream) }
        if let entry = app.watchHistory.entries.first { return .resume(entry) }
        if let movie = catalog.movieRails.first?.items.first { return .movie(movie) }
        if let series = catalog.seriesRails.first?.items.first { return .series(series) }
        return nil
    }

    var body: some View {
        NavigationStack(path: $model.path) {
            VStack(alignment: .leading, spacing: 0) {
                billboard
                    .focusSection()
                rows
                    .focusSection()
            }
            .navigationDestination(for: VODStream.self) { movie in
                MovieDetailView(client: model.client, movie: movie)
            }
            .navigationDestination(for: SeriesItem.self) { item in
                SeriesDetailView(client: model.client, series: item)
            }
        }
        .task { await catalog.load() }
        .task(id: focus) { await focusRested() }
        .task(id: billboardItem) { await loadDetails() }
        .playerCover(item: $nowPlaying)
    }

    /// Restarted on every focus change, which cancels the run before it, so
    /// the billboard only changes once focus stays put.
    private func focusRested() async {
        guard let item = focus?.item, item != shown else { return }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        shown = item
    }

    private func loadDetails() async {
        guard case .movie(let movie)? = billboardItem else { return }
        try? await Task.sleep(for: .milliseconds(400))
        guard !Task.isCancelled else { return }
        await model.loadInfo(for: movie)
    }

    // MARK: Billboard

    @ViewBuilder
    private var billboard: some View {
        if let item = billboardItem {
            let content = billboardContent(for: item)
            HStack(alignment: .bottom, spacing: 40) {
                VStack(alignment: .leading, spacing: 12) {
                    content.eyebrow
                    Text(content.title)
                        .font(.tvDisplay)
                        .foregroundStyle(Theme.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    // Blank lines hold the height, so the rows below don't
                    // jump as focus moves between items with more or less to say.
                    Text(content.meta.isEmpty ? " " : content.meta)
                        .font(.tvMeta)
                        .foregroundStyle(Theme.muted)
                        .lineLimit(1)
                    Text(content.detail ?? " ")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.text.opacity(0.85))
                        .lineLimit(2, reservesSpace: true)
                        .frame(maxWidth: 900, alignment: .leading)
                    billboardButtons(for: item)
                        .padding(.top, 8)
                }
                Spacer(minLength: 0)
                if case .live(let stream) = item {
                    ChannelLogo(url: stream.iconURL, size: CGSize(width: 360, height: 220))
                }
            }
            .padding(36)
            .frame(maxWidth: .infinity, minHeight: 420, maxHeight: 420, alignment: .bottomLeading)
            .background {
                HeroArt(url: content.artURL)
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, 40)
            .padding(.top, 12)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Discover")
                Wordmark(size: 76)
            }
            .padding(.horizontal, 40)
            .padding(.top, 12)
        }
    }

    private struct BillboardContent {
        var eyebrow: AnyView
        var title: String
        var meta = ""
        var detail: String?
        var artURL: URL?
    }

    private func billboardContent(for item: BillboardItem) -> BillboardContent {
        switch item {
        case .live(let stream):
            let now = epg.now
            let schedule = epg.schedule(for: stream.id)
            let current = schedule?.current(at: now)
            var content = BillboardContent(
                eyebrow: AnyView(LiveEyebrow(channel: stream.displayName.title)),
                title: current?.title ?? stream.displayName.title
            )
            if let current {
                let left = remainingLabel(minutes: EPGSchedule.minutesLeft(in: current, at: now))
                content.meta = "\(current.start.shortTime) to \(current.end.shortTime) · \(left)"
            } else if schedule != nil {
                content.meta = "No guide data"
            }
            if let next = schedule?.next(after: now) {
                content.detail = "Next: \(next.title), \(next.start.shortTime)"
            }
            return content

        case .resume(let entry):
            var parts: [String] = []
            if let subtitle = entry.subtitle { parts.append(subtitle) }
            if let duration = entry.duration, duration > entry.position {
                parts.append(remainingLabel(minutes: max(1, Int((duration - entry.position) / 60))))
            }
            return BillboardContent(
                eyebrow: AnyView(Eyebrow(text: "Continue watching")),
                title: entry.title,
                meta: parts.joined(separator: " · "),
                detail: "Stopped at \(clockString(entry.position)).",
                artURL: entry.artworkURL
            )

        case .movie(let movie):
            let info = model.movieInfo[movie.id]
            var parts: [String] = []
            if let year = info?.year { parts.append(year) }
            if let genre = info?.genre { parts.append(genre) }
            if let seconds = info?.durationSeconds, seconds > 0 { parts.append("\(seconds / 60) min") }
            if let rating = info?.rating ?? movie.rating, rating > 0 { parts.append(String(format: "★ %.1f", rating)) }
            return BillboardContent(
                eyebrow: AnyView(Eyebrow(text: "Movie")),
                title: info?.name ?? movie.name,
                meta: parts.joined(separator: " · "),
                detail: info?.plot,
                artURL: info?.backdropURL ?? movie.posterURL
            )

        case .series(let series):
            var parts: [String] = []
            if let year = series.seriesYear { parts.append(year) }
            if let genre = series.genre, !genre.isEmpty { parts.append(genre) }
            if let rating = series.rating, rating > 0 { parts.append(String(format: "★ %.1f", rating)) }
            return BillboardContent(
                eyebrow: AnyView(Eyebrow(text: "Series")),
                title: series.name,
                meta: parts.joined(separator: " · "),
                detail: series.plot,
                artURL: series.coverURL
            )
        }
    }

    @ViewBuilder
    private func billboardButtons(for item: BillboardItem) -> some View {
        HStack(spacing: 20) {
            switch item {
            case .live(let stream):
                Button {
                    play(stream)
                } label: {
                    Label("Watch live", systemImage: "play.fill")
                }
                .buttonStyle(TVActionButtonStyle(prominent: true))

                Button(app.multiview.tiles.contains(stream.id) ? "Open Multiview" : "Add to Multiview") {
                    app.multiview.addAndOpen(stream, limit: app.multiviewLimit)
                }
                .buttonStyle(TVActionButtonStyle())

            case .resume(let entry):
                Button {
                    nowPlaying = PlayableItem.resuming(entry, client: model.client)
                } label: {
                    Label("Resume", systemImage: "play.fill")
                }
                .buttonStyle(TVActionButtonStyle(prominent: true))

            case .movie(let movie):
                let saved = app.watchHistory.entry(id: "movie-\(movie.id)")
                Button {
                    play(movie, from: saved?.position)
                } label: {
                    Label(saved == nil ? "Play" : "Resume", systemImage: "play.fill")
                }
                .buttonStyle(TVActionButtonStyle(prominent: true))

                Button("Details") {
                    model.path.append(movie)
                }
                .buttonStyle(TVActionButtonStyle())

            case .series(let series):
                Button("Episodes") {
                    model.path.append(series)
                }
                .buttonStyle(TVActionButtonStyle(prominent: true))
            }
        }
    }

    // MARK: Rows

    private var rows: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 28) {
                liveRail
                resumeRail
                catalogRails

                if catalog.state != .loaded {
                    LoadStateView(state: catalog.state, emptyText: "Nothing to show yet") { catalog.retry() }
                        .frame(maxWidth: .infinity, minHeight: 240)
                }
            }
            .padding(.top, 20)
            .padding(.bottom, 60)
        }
        // Focused cards scale past the scroll view's edge; don't cut off their rings.
        .scrollClipDisabled()
        // But keep them from sliding up over the billboard.
        .mask {
            Rectangle()
                .padding(.horizontal, -400)
                .padding(.bottom, -400)
        }
    }

    @ViewBuilder
    private var liveRail: some View {
        if !app.favorites.channels.isEmpty {
            let rail = "live"
            Rail(title: "Live now on your favorites") {
                ForEach(app.favorites.channels) { stream in
                    Button {
                        play(stream)
                    } label: {
                        LiveNowCard(stream: stream, epg: epg)
                    }
                    .buttonStyle(TVCardButtonStyle())
                    .focused($focus, equals: Tile(rail: rail, item: .live(stream)))
                    .contextMenu {
                        Button(app.multiview.tiles.contains(stream.id) ? "Open Multiview" : "Add to Multiview") {
                            app.multiview.addAndOpen(stream, limit: app.multiviewLimit)
                        }
                    }
                    .onAppear { epg.rowAppeared(stream.id) }
                    .onDisappear { epg.rowDisappeared(stream.id) }
                }
            }
        }
    }

    @ViewBuilder
    private var resumeRail: some View {
        if !app.watchHistory.entries.isEmpty {
            let rail = "resume"
            Rail(title: "Continue watching") {
                ForEach(app.watchHistory.entries) { entry in
                    Button {
                        nowPlaying = PlayableItem.resuming(entry, client: model.client)
                    } label: {
                        PosterCard(
                            title: entry.title,
                            subtitle: entry.subtitle,
                            imageURL: entry.artworkURL,
                            width: Self.posterWidth,
                            progress: entry.progress
                        )
                    }
                    .buttonStyle(TVCardButtonStyle())
                    .focused($focus, equals: Tile(rail: rail, item: .resume(entry)))
                    .contextMenu {
                        Button("Remove from Continue Watching", role: .destructive) {
                            app.watchHistory.remove(id: entry.id)
                        }
                    }
                }
            }
        }
    }

    /// Movie and series rails, taking turns.
    @ViewBuilder
    private var catalogRails: some View {
        let count = max(catalog.movieRails.count, catalog.seriesRails.count)
        ForEach(0..<count, id: \.self) { index in
            if index < catalog.movieRails.count {
                movieRail(catalog.movieRails[index])
            }
            if index < catalog.seriesRails.count {
                seriesRail(catalog.seriesRails[index])
            }
        }
    }

    private func movieRail(_ rail: HomeModel.Rail<VODStream>) -> some View {
        let railID = "movies-\(rail.id)"
        return Rail(title: "Movies · \(rail.category.name)") {
            ForEach(rail.items) { movie in
                Button {
                    model.path.append(movie)
                } label: {
                    PosterCard(title: movie.name, imageURL: movie.posterURL, width: Self.posterWidth)
                }
                .buttonStyle(TVCardButtonStyle())
                .focused($focus, equals: Tile(rail: railID, item: .movie(movie)))
            }
        }
    }

    private func seriesRail(_ rail: HomeModel.Rail<SeriesItem>) -> some View {
        let railID = "series-\(rail.id)"
        return Rail(title: "Series · \(rail.category.name)") {
            ForEach(rail.items) { item in
                Button {
                    model.path.append(item)
                } label: {
                    PosterCard(title: item.name, subtitle: item.seriesYear, imageURL: item.coverURL, width: Self.posterWidth)
                }
                .buttonStyle(TVCardButtonStyle())
                .focused($focus, equals: Tile(rail: railID, item: .series(item)))
            }
        }
    }

    // MARK: Playing

    private func play(_ stream: LiveStream) {
        nowPlaying = PlayableItem(
            id: "live-\(stream.id)",
            title: stream.displayName.title,
            url: model.client.liveURL(streamID: stream.id, format: app.liveFormat),
            isLive: true
        )
    }

    private func play(_ movie: VODStream, from position: Double?) {
        let info = model.movieInfo[movie.id]
        nowPlaying = PlayableItem.movie(
            movie,
            title: info?.name ?? movie.name,
            subtitle: info?.year,
            artworkURL: info?.posterURL ?? movie.posterURL,
            client: model.client,
            startAt: position
        )
    }
}

/// A red dot, "Live" and the channel's name, above the billboard's title.
private struct LiveEyebrow: View {
    let channel: String

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(Theme.live)
                .frame(width: 12, height: 12)
            Text("Live · \(channel)".uppercased())
                .font(.tvEyebrow)
                .tracking(3)
                .foregroundStyle(Theme.live)
                .lineLimit(1)
        }
    }
}
