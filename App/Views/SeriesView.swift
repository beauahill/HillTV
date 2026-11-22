import HillTVCore
import Observation
import SwiftUI

@MainActor
@Observable
final class SeriesModel {
    let client: XtreamClient

    private(set) var categories: [XtreamCategory] = []
    private(set) var categoryState: LoadState = .idle
    private(set) var selectedCategoryID: String?
    private(set) var items: [SeriesItem] = []
    private(set) var itemState: LoadState = .idle
    /// Pushed detail pages. Empty means Series is at its root.
    var path = NavigationPath()

    private var cache: [String: [SeriesItem]] = [:]
    private var selectTask: Task<Void, Never>?
    private let visibility: CategoryVisibilityStore

    init(client: XtreamClient, visibility: CategoryVisibilityStore) {
        self.client = client
        self.visibility = visibility
    }

    /// Categories the user hasn't hidden.
    var visibleCategories: [XtreamCategory] {
        visibility.visible(categories, in: .series)
    }

    var allHidden: Bool {
        !categories.isEmpty && visibleCategories.isEmpty
    }

    func loadCategories() async {
        if !categories.isEmpty {
            await reconcileSelection()
            return
        }
        guard categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.seriesCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            await reconcileSelection()
        } catch {
            // Leaving the section cancels the request; retry next time instead of showing an error.
            categoryState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    /// Keeps the selection on a category that is still shown, since categories
    /// can be hidden in Settings between visits. Also refetches after a
    /// cancelled first load.
    private func reconcileSelection() async {
        let visible = visibleCategories
        if let id = selectedCategoryID, visible.contains(where: { $0.id == id }) {
            if itemState == .idle { await select(id) }
        } else if let first = visible.first {
            await select(first.id)
        } else {
            selectedCategoryID = nil
            items = []
            itemState = .idle
        }
    }

    func scheduleSelect(_ categoryID: String) {
        selectTask?.cancel()
        selectTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            await self?.select(categoryID)
        }
    }

    func select(_ categoryID: String) async {
        if categoryID == selectedCategoryID, itemState == .loaded || itemState == .loading { return }
        selectedCategoryID = categoryID
        if let cached = cache[categoryID] {
            items = cached
            itemState = cached.isEmpty ? .empty : .loaded
            return
        }
        items = []
        itemState = .loading
        do {
            let result = try await client.series(categoryID: categoryID)
            cache[categoryID] = result
            guard selectedCategoryID == categoryID else { return }
            items = result
            itemState = result.isEmpty ? .empty : .loaded
        } catch {
            guard selectedCategoryID == categoryID else { return }
            itemState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    func retry() {
        guard let id = selectedCategoryID else { return }
        itemState = .idle
        Task { await select(id) }
    }
}

struct SeriesView: View {
    @Bindable var model: SeriesModel
    @FocusState private var focusedCategory: String?

    var body: some View {
        NavigationStack(path: $model.path) {
            HStack(alignment: .top, spacing: 24) {
                CategoryColumn(
                    title: "Series",
                    categories: model.visibleCategories,
                    selectedID: model.selectedCategoryID,
                    focus: $focusedCategory
                ) { category in
                    Task { await model.select(category.id) }
                }
                .frame(width: 360)
                .overlay {
                    LoadStateView(
                        state: model.allHidden ? .empty : model.categoryState,
                        emptyText: model.allHidden ? allCategoriesHiddenText : "No series categories"
                    )
                }

                PosterGrid(items: model.items) { item in
                    NavigationLink(value: item) {
                        PosterCard(title: item.name, subtitle: item.seriesYear, imageURL: item.coverURL)
                    }
                    .buttonStyle(TVCardButtonStyle())
                }
                .frame(maxWidth: .infinity)
                .overlay {
                    LoadStateView(state: model.itemState, emptyText: "No series in this category") { model.retry() }
                }
            }
            .navigationDestination(for: SeriesItem.self) { item in
                SeriesDetailView(client: model.client, series: item)
            }
        }
        .task { await model.loadCategories() }
        .onChange(of: focusedCategory) { _, newValue in
            if let newValue { model.scheduleSelect(newValue) }
        }
    }
}

extension SeriesItem {
    /// First four digits of the release date, when the panel sends one.
    var seriesYear: String? {
        guard let releaseDate, releaseDate.count >= 4 else { return nil }
        let year = String(releaseDate.prefix(4))
        return Int(year) == nil ? nil : year
    }
}

struct SeriesDetailView: View {
    let client: XtreamClient
    let series: SeriesItem

    @Environment(AppModel.self) private var app
    @State private var info: SeriesInfo?
    @State private var state: LoadState = .loading
    @State private var selectedSeason: Int?
    @State private var nowPlaying: PlayableItem?

    private var currentSeason: SeriesInfo.Season? {
        guard let info else { return nil }
        return info.seasons.first { $0.number == selectedSeason } ?? info.seasons.first
    }

    private var pills: [String] {
        var result: [String] = []
        if let year = series.seriesYear { result.append(year) }
        if let genre = series.genre, !genre.isEmpty { result.append(genre) }
        if let rating = series.rating, rating > 0 { result.append(String(format: "★ %.1f", rating)) }
        if let info, !info.seasons.isEmpty {
            result.append(info.seasons.count == 1 ? "1 season" : "\(info.seasons.count) seasons")
        }
        return result
    }

    var body: some View {
        HStack(alignment: .top, spacing: 56) {
            overview
                .frame(width: 460)
            episodes
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 32)
        .background { Backdrop(url: info?.coverURL ?? series.coverURL) }
        .task { await load() }
        .playerCover(item: $nowPlaying)
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: 20) {
            AsyncImage(url: info?.coverURL ?? series.coverURL) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    Theme.control
                }
            }
            .frame(width: 240, height: 360)
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))

            Eyebrow(text: "Series")
            Text(series.name)
                .font(.tvSubtitle)
                .foregroundStyle(Theme.text)
                .lineLimit(3)
            FlowLayout(spacing: 10) {
                ForEach(pills, id: \.self) { Pill(text: $0) }
            }
            if let plot = info?.plot ?? series.plot {
                Text(plot)
                    .font(.tvSmall)
                    .foregroundStyle(Theme.muted)
                    .lineSpacing(4)
                    .lineLimit(6)
            }
            Spacer(minLength: 0)
        }
    }

    private var episodes: some View {
        VStack(alignment: .leading, spacing: 16) {
            Eyebrow(text: "Episodes")
                .padding(.leading, 12)

            if let info, info.seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 20) {
                        ForEach(info.seasons) { season in
                            Button("Season \(season.number)") {
                                selectedSeason = season.number
                            }
                            .buttonStyle(TVActionButtonStyle(isSelected: season.number == currentSeason?.number))
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 16)
                }
                .scrollClipDisabled()
                // Room for the focus ring, but not enough to slide chips over the overview column.
                .mask { Rectangle().padding(-16) }
            }

            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(currentSeason?.episodes ?? []) { episode in
                        Button {
                            play(episode)
                        } label: {
                            EpisodeRow(episode: episode, progress: app.watchHistory.entry(id: "episode-\(episode.id)")?.progress)
                        }
                        .buttonStyle(TVRowButtonStyle(minHeight: Theme.listRowHeight))
                    }
                }
                .padding(12)
            }
            .overlay { LoadStateView(state: state, emptyText: "No episodes listed") { Task { await load() } } }
        }
    }

    private func load() async {
        guard info == nil else { return }
        state = .loading
        do {
            let result = try await client.seriesInfo(seriesID: series.id)
            info = result
            selectedSeason = result.seasons.first?.number
            state = result.seasons.isEmpty ? .empty : .loaded
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func play(_ episode: Episode) {
        let season = episode.season ?? currentSeason?.number
        let label = [season.map { "S\($0)" }, episode.episodeNumber.map { "E\($0)" }].compactMap { $0 }.joined()
        let subtitle = label.isEmpty ? episode.title : "\(label) · \(episode.title)"
        let template = WatchEntry(
            kind: .episode,
            streamID: episode.id,
            containerExtension: episode.containerExtension,
            title: series.name,
            subtitle: subtitle,
            artworkURL: info?.coverURL ?? series.coverURL,
            position: 0,
            duration: nil
        )
        // Episodes resume automatically; the row's bar shows how far in.
        nowPlaying = PlayableItem(
            id: "episode-\(episode.id)",
            title: series.name,
            subtitle: subtitle,
            url: client.episodeURL(episode),
            isLive: false,
            history: template,
            startAt: app.watchHistory.entry(id: template.id)?.position
        )
    }
}

private struct EpisodeRow: View {
    let episode: Episode
    var progress: Double?

    var body: some View {
        HStack(spacing: 24) {
            Text("\(episode.episodeNumber ?? 0)")
                .font(.tvMeta.monospacedDigit())
                .tvSecondary()
                .frame(width: 56, alignment: .trailing)
            VStack(alignment: .leading, spacing: 6) {
                Text(episode.title)
                    .font(.tvMeta.weight(.semibold))
                    .lineLimit(1)
                if let plot = episode.plot {
                    Text(plot)
                        .font(.tvSmall)
                        .tvSecondary()
                        .lineLimit(2)
                }
                if let progress {
                    ProgressBar(value: progress)
                        .frame(maxWidth: 320)
                        .padding(.top, 4)
                }
            }
            Spacer(minLength: 12)
            if let seconds = episode.durationSeconds, seconds > 0 {
                Text("\(seconds / 60) min")
                    .font(.tvSmall)
                    .tvSecondary()
            }
        }
    }
}
