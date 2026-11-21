import HillTVCore
import Observation
import SwiftUI

/// Home: a featured movie up top, then rails for favorite channels and the
/// first few movie and series categories. Uses per-category requests rather
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

    private static let railCount = 3
    private static let itemsPerRail = 20

    init(client: XtreamClient) {
        self.client = client
    }

    func load() async {
        guard state == .idle else { return }
        state = .loading

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
    let model: HomeModel

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?

    var body: some View {
        NavigationStack {
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 56) {
                    hero

                    if !app.favorites.channels.isEmpty {
                        rail(title: "Favorite channels") {
                            ForEach(app.favorites.channels) { stream in
                                Button {
                                    play(stream)
                                } label: {
                                    ChannelTile(stream: stream)
                                }
                                .buttonStyle(TVCardButtonStyle())
                            }
                        }
                    }

                    ForEach(model.movieRails) { rail in
                        self.rail(title: "Movies · \(rail.category.name)") {
                            ForEach(rail.items) { movie in
                                NavigationLink(value: movie) {
                                    PosterCard(title: movie.name, imageURL: movie.posterURL, width: 200)
                                }
                                .buttonStyle(TVCardButtonStyle())
                            }
                        }
                    }

                    ForEach(model.seriesRails) { rail in
                        self.rail(title: "Series · \(rail.category.name)") {
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

    @ViewBuilder
    private var hero: some View {
        if let movie = model.featured {
            let info = model.featuredInfo
            VStack(alignment: .leading, spacing: 20) {
                Eyebrow(text: "Featured")
                Text(info?.name ?? movie.name)
                    .font(.tvDisplay)
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                let meta = [info?.year, info?.genre].compactMap { $0 }.joined(separator: " · ")
                if !meta.isEmpty {
                    Text(meta)
                        .font(.tvMeta)
                        .foregroundStyle(Theme.muted)
                }
                if let plot = info?.plot {
                    Text(plot)
                        .font(.tvMeta)
                        .foregroundStyle(Theme.text.opacity(0.9))
                        .lineSpacing(6)
                        .lineLimit(3)
                        .frame(maxWidth: 820, alignment: .leading)
                }
                NavigationLink(value: movie) {
                    Label("Explore title", systemImage: "arrow.right")
                        .labelStyle(TrailingIconLabelStyle())
                }
                .buttonStyle(TVActionButtonStyle(prominent: true))
                .padding(.top, 12)
            }
            .padding(.horizontal, 40)
            .padding(.top, 40)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity, minHeight: 560, alignment: .bottomLeading)
            .background {
                HeroArt(url: info?.backdropURL ?? movie.posterURL)
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Home")
                Wordmark(size: 76)
            }
            .padding(.horizontal, 40)
            .padding(.top, 24)
        }
    }

    private func rail<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.tvHeading)
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 40)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 32) {
                    content()
                }
                .padding(.horizontal, 40)
                .padding(.vertical, 24)
            }
        }
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

/// Unblurred artwork faded into the background on the left and bottom.
private struct HeroArt: View {
    let url: URL?

    var body: some View {
        ZStack {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image
                        .resizable()
                        .scaledToFill()
                        .opacity(0.75)
                }
            }
            LinearGradient(
                stops: [
                    .init(color: Theme.background, location: 0),
                    .init(color: Theme.background.opacity(0.7), location: 0.4),
                    .init(color: .clear, location: 0.8),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            LinearGradient(
                colors: [.clear, Theme.background],
                startPoint: .center,
                endPoint: .bottom
            )
        }
        .clipped()
        .ignoresSafeArea(edges: .top)
    }
}

/// Logo card for a live channel in a rail.
private struct ChannelTile: View {
    let stream: LiveStream
    @Environment(\.isFocused) private var isFocused

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ChannelLogo(url: stream.iconURL, size: CGSize(width: 260, height: 160))
                .overlay {
                    if isFocused {
                        RoundedRectangle(cornerRadius: 17, style: .continuous)
                            .stroke(Theme.accent, lineWidth: Theme.focusRing)
                            .padding(-9)
                    }
                }
                .scaleEffect(isFocused ? Theme.focusScale : 1)
            Text(stream.name)
                .font(.tvSmall.weight(.semibold))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .frame(width: 260, alignment: .leading)
        }
        .animation(Theme.focusAnimation, value: isFocused)
    }
}

private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 12) {
            configuration.title
            configuration.icon
        }
    }
}
