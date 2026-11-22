import HillTVCore
import Observation
import SwiftUI

/// Search across channels, movies and series. Xtream has no search endpoint,
/// so the first visit downloads the three full catalogs once and indexes
/// them off the main thread. Each keystroke then scans the folded names.
@MainActor
@Observable
final class SearchModel {
    let client: XtreamClient

    var query = "" {
        didSet { scheduleSearch() }
    }

    private(set) var catalogState: LoadState = .idle
    private(set) var channels: [LiveStream] = []
    private(set) var movies: [VODStream] = []
    private(set) var series: [SeriesItem] = []
    /// Pushed detail pages. Empty means Search is at its root.
    var path = NavigationPath()

    private var liveIndex: SearchIndex<LiveStream>?
    private var vodIndex: SearchIndex<VODStream>?
    private var seriesIndex: SearchIndex<SeriesItem>?
    private var searchTask: Task<Void, Never>?

    private static let resultsPerRow = 40
    /// Asked of the index before hidden categories are filtered out.
    private static let searchDepth = 400
    private let visibility: CategoryVisibilityStore

    init(client: XtreamClient, visibility: CategoryVisibilityStore) {
        self.client = client
        self.visibility = visibility
    }

    var hasResults: Bool {
        !channels.isEmpty || !movies.isEmpty || !series.isEmpty
    }

    func loadCatalog() async {
        guard catalogState == .idle else { return }
        catalogState = .loading

        let client = client
        async let live = Self.attempt { try await client.liveStreams() }
        async let vod = Self.attempt { try await client.vodStreams() }
        async let shows = Self.attempt { try await client.series() }
        let results = (await live, await vod, await shows)

        // Leaving Search cancels the downloads; start over next visit.
        if Task.isCancelled {
            catalogState = .idle
            return
        }

        let liveItems = (try? results.0.get()) ?? []
        let vodItems = (try? results.1.get()) ?? []
        let seriesItems = (try? results.2.get()) ?? []
        if liveItems.isEmpty, vodItems.isEmpty, seriesItems.isEmpty {
            if case .failure(let error) = results.0 {
                catalogState = .failed(error.localizedDescription)
            } else {
                catalogState = .empty
            }
            return
        }

        let indexes = await Task.detached(priority: .userInitiated) {
            (
                SearchIndex(items: liveItems) { $0.name },
                SearchIndex(items: vodItems) { $0.name },
                SearchIndex(items: seriesItems) { $0.name }
            )
        }.value
        liveIndex = indexes.0
        vodIndex = indexes.1
        seriesIndex = indexes.2
        catalogState = .loaded
        scheduleSearch()
    }

    func retry() {
        catalogState = .idle
        Task { await loadCatalog() }
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let liveIndex, let vodIndex, let seriesIndex else {
            channels = []
            movies = []
            series = []
            return
        }
        let limit = Self.resultsPerRow
        let depth = Self.searchDepth
        let hiding = visibility.visibility
        searchTask = Task { [weak self] in
            // Wait for a pause in typing, and drop searches the user typed past.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            let found = await Task.detached(priority: .userInitiated) {
                // Hidden categories stay out of results, the same as everywhere else.
                (
                    Array(liveIndex.search(text, limit: depth)
                        .filter { !hiding.isHidden($0.categoryID ?? "", in: .live) }.prefix(limit)),
                    Array(vodIndex.search(text, limit: depth)
                        .filter { !hiding.isHidden($0.categoryID ?? "", in: .movies) }.prefix(limit)),
                    Array(seriesIndex.search(text, limit: depth)
                        .filter { !hiding.isHidden($0.categoryID ?? "", in: .series) }.prefix(limit))
                )
            }.value
            guard !Task.isCancelled, let self else { return }
            self.channels = found.0
            self.movies = found.1
            self.series = found.2
        }
    }

    private nonisolated static func attempt<T: Sendable>(_ operation: @Sendable () async throws -> [T]) async -> Result<[T], Error> {
        do {
            return .success(try await operation())
        } catch {
            return .failure(error)
        }
    }
}

struct SearchView: View {
    @Bindable var model: SearchModel

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?

    var body: some View {
        NavigationStack(path: $model.path) {
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 40) {
                    status

                    if !model.channels.isEmpty {
                        Rail(title: "Channels") {
                            ForEach(model.channels) { stream in
                                Button {
                                    play(stream)
                                } label: {
                                    ChannelTile(stream: stream)
                                }
                                .buttonStyle(TVCardButtonStyle())
                                .contextMenu {
                                    Button(app.favorites.contains(stream.id) ? "Remove from Favorites" : "Add to Favorites") {
                                        app.favorites.toggle(stream)
                                    }
                                }
                            }
                        }
                    }

                    if !model.movies.isEmpty {
                        Rail(title: "Movies") {
                            ForEach(model.movies) { movie in
                                NavigationLink(value: movie) {
                                    PosterCard(title: movie.name, imageURL: movie.posterURL, width: 200)
                                }
                                .buttonStyle(TVCardButtonStyle())
                            }
                        }
                    }

                    if !model.series.isEmpty {
                        Rail(title: "Series") {
                            ForEach(model.series) { item in
                                NavigationLink(value: item) {
                                    PosterCard(title: item.name, subtitle: item.seriesYear, imageURL: item.coverURL, width: 200)
                                }
                                .buttonStyle(TVCardButtonStyle())
                            }
                        }
                    }
                }
                .padding(.vertical, 24)
            }
            .scrollClipDisabled()
            .searchable(text: $model.query, prompt: "Channels, movies and series")
            .navigationDestination(for: VODStream.self) { movie in
                MovieDetailView(client: model.client, movie: movie)
            }
            .navigationDestination(for: SeriesItem.self) { item in
                SeriesDetailView(client: model.client, series: item)
            }
        }
        .task { await model.loadCatalog() }
        .playerCover(item: $nowPlaying)
    }

    @ViewBuilder
    private var status: some View {
        let trimmed = model.query.trimmingCharacters(in: .whitespacesAndNewlines)
        switch model.catalogState {
        case .idle, .loading:
            HStack(spacing: 20) {
                ProgressView()
                    .tint(Theme.accent)
                Text("Loading the catalog for search. Big providers can take a moment.")
                    .font(.tvMeta)
                    .foregroundStyle(Theme.muted)
            }
            .padding(.horizontal, 40)
        case .failed, .empty:
            LoadStateView(state: model.catalogState, emptyText: "Nothing to search") { model.retry() }
                .frame(maxWidth: .infinity, minHeight: 300)
        case .loaded:
            if trimmed.isEmpty {
                hint("Search by name across live channels, movies and series.")
            } else if !model.hasResults {
                hint("No matches for \"\(trimmed)\".")
            }
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.tvMeta)
            .foregroundStyle(Theme.muted)
            .padding(.horizontal, 40)
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
