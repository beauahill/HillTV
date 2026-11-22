import HillTVCore
import Observation
import SwiftUI

/// Home: search and the time, a featured movie, what's live on favorite
/// channels, continue watching, then rails for the first few movie and
/// series categories. Uses per-category requests rather
/// than the full catalog, which can be tens of megabytes on big panels.
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
    private(set) var featured: VODStream?
    private(set) var featuredInfo: VODInfo?
    private(set) var state: LoadState = .idle
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

        featured = movieRails.lazy.flatMap(\.items).first { $0.posterURL != nil }
        if let featured {
            featuredInfo = try? await client.vodInfo(streamID: featured.id)
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

struct HomeView: View {
    @Bindable var model: HomeModel
    let epg: EPGStore
    /// Jumps to the Search section.
    var onSearch: () -> Void = {}

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?

    var body: some View {
        NavigationStack(path: $model.path) {
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 44) {
                    topBar

                    hero

                    if !app.favorites.channels.isEmpty {
                        Rail(title: "Live now on your favorites") {
                            ForEach(app.favorites.channels) { stream in
                                Button {
                                    play(stream)
                                } label: {
                                    LiveNowCard(stream: stream, epg: epg)
                                }
                                .buttonStyle(TVCardButtonStyle())
                                .onAppear { epg.rowAppeared(stream.id) }
                                .onDisappear { epg.rowDisappeared(stream.id) }
                            }
                        }
                    }

                    if !app.watchHistory.entries.isEmpty {
                        Rail(title: "Continue watching") {
                            ForEach(app.watchHistory.entries) { entry in
                                Button {
                                    nowPlaying = PlayableItem.resuming(entry, client: model.client)
                                } label: {
                                    PosterCard(
                                        title: entry.title,
                                        subtitle: entry.subtitle,
                                        imageURL: entry.artworkURL,
                                        width: 200,
                                        progress: entry.progress
                                    )
                                }
                                .buttonStyle(TVCardButtonStyle())
                                .contextMenu {
                                    Button("Remove from Continue Watching", role: .destructive) {
                                        app.watchHistory.remove(id: entry.id)
                                    }
                                }
                            }
                        }
                    }

                    ForEach(model.movieRails) { rail in
                        Rail(title: "Movies · \(rail.category.name)") {
                            ForEach(rail.items) { movie in
                                NavigationLink(value: movie) {
                                    PosterCard(title: movie.name, imageURL: movie.posterURL, width: 200)
                                }
                                .buttonStyle(TVCardButtonStyle())
                            }
                        }
                    }

                    ForEach(model.seriesRails) { rail in
                        Rail(title: "Series · \(rail.category.name)") {
                            ForEach(rail.items) { item in
                                NavigationLink(value: item) {
                                    PosterCard(title: item.name, subtitle: item.seriesYear, imageURL: item.coverURL, width: 200)
                                }
                                .buttonStyle(TVCardButtonStyle())
                            }
                        }
                    }

                    if model.state != .loaded {
                        LoadStateView(state: model.state, emptyText: "Nothing to show yet") { model.retry() }
                            .frame(maxWidth: .infinity, minHeight: 300)
                    }
                }
                .padding(.bottom, 60)
            }
            // Focused posters scale past the scroll view's edge; don't cut off their rings.
            .scrollClipDisabled()
            .navigationDestination(for: VODStream.self) { movie in
                MovieDetailView(client: model.client, movie: movie)
            }
            .navigationDestination(for: SeriesItem.self) { item in
                SeriesDetailView(client: model.client, series: item)
            }
        }
        .task { await model.load() }
        .playerCover(item: $nowPlaying)
    }

    private var topBar: some View {
        HStack(spacing: 24) {
            Button(action: onSearch) {
                Label("Search channels, movies and series", systemImage: "magnifyingglass")
            }
            .buttonStyle(TVChipButtonStyle())

            Spacer(minLength: 24)

            TimelineView(.everyMinute) { context in
                Text(context.date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().hour().minute()))
                    .font(.tvSmall)
                    .foregroundStyle(Theme.muted)
            }
        }
        .padding(.horizontal, 40)
        .padding(.top, 12)
    }

    @ViewBuilder
    private var hero: some View {
        if let movie = model.featured {
            let info = model.featuredInfo
            let saved = app.watchHistory.entry(id: "movie-\(movie.id)")
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: "Featured movie")
                Text(info?.name ?? movie.name)
                    .font(.tvDisplay)
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                let meta = heroMeta(movie: movie, info: info)
                if !meta.isEmpty {
                    Text(meta)
                        .font(.tvMeta)
                        .foregroundStyle(Theme.muted)
                        .lineLimit(1)
                }
                HStack(spacing: 20) {
                    Button {
                        play(movie, info: info, from: saved?.position)
                    } label: {
                        Label(saved == nil ? "Play" : "Resume", systemImage: "play.fill")
                    }
                    .buttonStyle(TVActionButtonStyle(prominent: true))

                    NavigationLink(value: movie) {
                        Text("Details")
                    }
                    .buttonStyle(TVActionButtonStyle())
                }
                .padding(.top, 10)
            }
            .padding(36)
            .frame(maxWidth: .infinity, minHeight: 340, alignment: .bottomLeading)
            .background {
                HeroArt(url: info?.backdropURL ?? movie.posterURL)
            }
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, 40)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Home")
                Wordmark(size: 76)
            }
            .padding(.horizontal, 40)
        }
    }

    private func heroMeta(movie: VODStream, info: VODInfo?) -> String {
        var parts: [String] = []
        if let year = info?.year { parts.append(year) }
        if let genre = info?.genre { parts.append(genre) }
        if let seconds = info?.durationSeconds, seconds > 0 { parts.append("\(seconds / 60) min") }
        if let rating = info?.rating ?? movie.rating, rating > 0 { parts.append(String(format: "★ %.1f", rating)) }
        return parts.joined(separator: " · ")
    }

    private func play(_ movie: VODStream, info: VODInfo?, from position: Double?) {
        nowPlaying = PlayableItem.movie(
            movie,
            title: info?.name ?? movie.name,
            subtitle: info?.year,
            artworkURL: info?.posterURL ?? movie.posterURL,
            client: model.client,
            startAt: position
        )
    }

    private func play(_ stream: LiveStream) {
        nowPlaying = PlayableItem(
            id: "live-\(stream.id)",
            title: stream.name,
            url: model.client.liveURL(streamID: stream.id, format: app.liveFormat),
            isLive: true
        )
    }
}

/// Unblurred artwork on the right of the hero card, faded into the surface on the left.
private struct HeroArt: View {
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
