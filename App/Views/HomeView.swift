import HillTVCore
import Observation
import SwiftUI

/// Something Home can show in its billboard.
enum BillboardItem: Hashable {
    case live(LiveStream)
    /// A continue-watching entry, by its ID. The entry itself changes every
    /// few seconds of playback, so it is read from the store when needed.
    case resume(String)
    case movie(VODStream)
    case series(SeriesItem)
}

/// Home's data: rails for the first few movie and series categories, and
/// the details of whichever movie the billboard shows. Uses per-category
/// requests rather than the full catalog, which can be tens of megabytes on
/// big panels.
@MainActor
@Observable
final class HomeModel {
    struct Rail<Item: Identifiable & Hashable>: Identifiable {
        let category: XtreamCategory
        let items: [Item]
        var id: String { category.id }
    }

    let client: XtreamClient

    private(set) var movieRails: [Rail<VODStream>] = []
    private(set) var seriesRails: [Rail<SeriesItem>] = []
    private(set) var state: LoadState = .idle
    /// Movie details fetched for the billboard, by stream ID.
    private(set) var movieInfo: [Int: VODInfo] = [:]
    /// Movies whose details were asked for and didn't come.
    private var missingInfo: Set<Int> = []
    /// Pushed detail pages. Empty means Home is at its root.
    var path = NavigationPath()

    private static let railCount = 3
    private static let itemsPerRail = 20
    private let visibility: CategoryVisibilityStore
    /// What was hidden when the rails were built. A change means rebuild.
    private var loadedWith: CategoryVisibility?

    init(client: XtreamClient, visibility: CategoryVisibilityStore) {
        self.client = client
        self.visibility = visibility
    }

    func load() async {
        // Categories hidden or shown in Settings since the last visit: build the rails again.
        if state == .loaded || state == .empty, loadedWith != visibility.visibility {
            state = .idle
        }
        guard state == .idle else { return }
        state = .loading
        let hiding = visibility.visibility

        // A failed or cancelled load must end somewhere retryable, never stuck.
        var firstError: Error?
        var movieCats: [XtreamCategory] = []
        var seriesCats: [XtreamCategory] = []
        do { movieCats = try await client.vodCategories() } catch { firstError = error }
        do { seriesCats = try await client.seriesCategories() } catch { firstError = firstError ?? error }
        if Task.isCancelled {
            state = .idle
            return
        }
        if movieCats.isEmpty, seriesCats.isEmpty, let firstError {
            state = .failed(firstError.localizedDescription)
            return
        }
        movieCats = hiding.visible(movieCats, in: .movies)
        seriesCats = hiding.visible(seriesCats, in: .series)

        async let movies = loadMovieRails(Array(movieCats.prefix(Self.railCount)))
        async let series = loadSeriesRails(Array(seriesCats.prefix(Self.railCount)))
        let loadedMovies = await movies
        let loadedSeries = await series
        if Task.isCancelled {
            state = .idle
            return
        }
        movieRails = loadedMovies
        seriesRails = loadedSeries
        loadedWith = hiding
        state = (movieRails.isEmpty && seriesRails.isEmpty) ? .empty : .loaded
    }

    /// Fetches a movie's plot and backdrop once. Called after focus has
    /// rested on the movie, so passing over a row doesn't ask for each one.
    func loadInfo(for movie: VODStream) async {
        guard movieInfo[movie.id] == nil, !missingInfo.contains(movie.id) else { return }
        do {
            movieInfo[movie.id] = try await client.vodInfo(streamID: movie.id)
        } catch {
            // Leaving the movie cancels the request; that isn't "no details".
            if !Task.isCancelled { missingInfo.insert(movie.id) }
        }
    }

    func retry() {
        state = .idle
        Task { await load() }
    }

    private func loadMovieRails(_ categories: [XtreamCategory]) async -> [Rail<VODStream>] {
        let client = client
        let limit = Self.itemsPerRail
        return await withTaskGroup(of: (Int, Rail<VODStream>?).self) { group in
            for (index, category) in categories.enumerated() {
                group.addTask {
                    let items = (try? await client.vodStreams(categoryID: category.id)) ?? []
                    return (index, items.isEmpty ? nil : Rail(category: category, items: Array(items.prefix(limit))))
                }
            }
            var results: [(Int, Rail<VODStream>)] = []
            for await (index, rail) in group {
                if let rail { results.append((index, rail)) }
            }
            return results.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    private func loadSeriesRails(_ categories: [XtreamCategory]) async -> [Rail<SeriesItem>] {
        let client = client
        let limit = Self.itemsPerRail
        return await withTaskGroup(of: (Int, Rail<SeriesItem>?).self) { group in
            for (index, category) in categories.enumerated() {
                group.addTask {
                    let items = (try? await client.series(categoryID: category.id)) ?? []
                    return (index, items.isEmpty ? nil : Rail(category: category, items: Array(items.prefix(limit))))
                }
            }
            var results: [(Int, Rail<SeriesItem>)] = []
            for await (index, rail) in group {
                if let rail { results.append((index, rail)) }
            }
            return results.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }
}

/// Home: a billboard on top that shows whatever has focus, live or on
/// demand, and rows underneath that mix both. Live channels first, then
/// continue watching, then movies and series.
///
/// Live channels have no picture here, only the provider's logo and the
/// guide's text: a picture would mean a connection per channel.
struct HomeView: View {
    @Bindable var model: HomeModel
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
        if let shown, stillThere(shown) { return shown }
        if let stream = app.favorites.channels.first { return .live(stream) }
        if let entry = app.watchHistory.entries.first { return .resume(entry.id) }
        if let movie = model.movieRails.first?.items.first { return .movie(movie) }
        if let series = model.seriesRails.first?.items.first { return .series(series) }
        return nil
    }

    /// A finished or removed continue-watching entry leaves the billboard.
    private func stillThere(_ item: BillboardItem) -> Bool {
        if case .resume(let id) = item { return app.watchHistory.entry(id: id) != nil }
        return true
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
        .task { await model.load() }
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
                Eyebrow(text: "Home")
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

        case .resume(let id):
            guard let entry = app.watchHistory.entry(id: id) else {
                return BillboardContent(eyebrow: AnyView(Eyebrow(text: "Continue watching")), title: " ")
            }
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

            case .resume(let id):
                Button {
                    // The entry as it is now, not as it was when focus was on it.
                    if let entry = app.watchHistory.entry(id: id) {
                        nowPlaying = PlayableItem.resuming(entry, client: model.client)
                    }
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
                browseRails

                if model.state != .loaded {
                    LoadStateView(state: model.state, emptyText: "Nothing to show yet") { model.retry() }
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
                    .focused($focus, equals: Tile(rail: rail, item: .resume(entry.id)))
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
    private var browseRails: some View {
        let count = max(model.movieRails.count, model.seriesRails.count)
        ForEach(0..<count, id: \.self) { index in
            if index < model.movieRails.count {
                movieRail(model.movieRails[index])
            }
            if index < model.seriesRails.count {
                seriesRail(model.seriesRails[index])
            }
        }
    }

    private func movieRail(_ rail: HomeModel.Rail<VODStream>) -> some View {
        let railID = "movies-\(rail.id)"
        return Rail(title: "Movies · \(rail.category.displayName.line)") {
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
        return Rail(title: "Series · \(rail.category.displayName.line)") {
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

/// Unblurred artwork on the right of the billboard, faded into the surface on the left.
struct HeroArt: View {
    let url: URL?

    var body: some View {
        ZStack {
            Theme.surface
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image
                        .resizable()
                        .scaledToFill()
                        .opacity(0.8)
                }
            }
            // A new image view per address. Without this the last picture
            // stays up when the address changes to none, as it does when the
            // billboard moves from a movie to a live channel.
            .id(url)
            LinearGradient(
                stops: [
                    .init(color: Theme.background, location: 0),
                    .init(color: Theme.background.opacity(0.75), location: 0.4),
                    .init(color: .clear, location: 0.85),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        }
        .clipped()
    }
}
