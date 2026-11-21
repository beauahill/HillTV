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

    private var cache: [String: [SeriesItem]] = [:]
    private var selectTask: Task<Void, Never>?

    init(client: XtreamClient) {
        self.client = client
    }

    func loadCategories() async {
        guard categories.isEmpty, categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.seriesCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            if let first = categories.first { await select(first.id) }
        } catch {
            categoryState = .failed(error.localizedDescription)
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
            itemState = .failed(error.localizedDescription)
        }
    }

    func retry() {
        guard let id = selectedCategoryID else { return }
        itemState = .idle
        Task { await select(id) }
    }
}

struct SeriesView: View {
    @State private var model: SeriesModel
    @FocusState private var focusedCategory: String?

    private let columns = [GridItem(.adaptive(minimum: 240, maximum: 260), spacing: 48)]

    init(client: XtreamClient) {
        _model = State(initialValue: SeriesModel(client: client))
    }

    var body: some View {
        NavigationStack {
            HStack(alignment: .top, spacing: 40) {
                CategoryColumn(
                    title: "Series",
                    categories: model.categories,
                    selectedID: model.selectedCategoryID,
                    focus: $focusedCategory
                ) { category in
                    Task { await model.select(category.id) }
                }
                .frame(width: 440)
                .overlay { LoadStateView(state: model.categoryState, emptyText: "No series categories") }

                ScrollView {
                    LazyVGrid(columns: columns, spacing: 60) {
                        ForEach(model.items) { item in
                            NavigationLink(value: item) {
                                PosterCard(title: item.name, imageURL: item.coverURL)
                            }
                            .buttonStyle(.card)
                        }
                    }
                    .padding(40)
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

struct SeriesDetailView: View {
    let client: XtreamClient
    let series: SeriesItem

    @State private var info: SeriesInfo?
    @State private var state: LoadState = .loading
    @State private var selectedSeason: Int?
    @State private var nowPlaying: PlayableItem?

    private var currentSeason: SeriesInfo.Season? {
        guard let info else { return nil }
        return info.seasons.first { $0.number == selectedSeason } ?? info.seasons.first
    }

    var body: some View {
        HStack(alignment: .top, spacing: 60) {
            VStack(alignment: .leading, spacing: 24) {
                AsyncImage(url: info?.coverURL ?? series.coverURL) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        Color.gray.opacity(0.25)
                    }
                }
                .frame(width: 320, height: 480)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Text(series.name)
                    .font(.title3.bold())
                if let genre = series.genre {
                    Text(genre)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let plot = info?.plot ?? series.plot {
                    Text(plot)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(10)
                }
                Spacer()
            }
            .frame(width: 420)

            VStack(alignment: .leading, spacing: 30) {
                if let info, info.seasons.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 20) {
                            ForEach(info.seasons) { season in
                                Button("Season \(season.number)") {
                                    selectedSeason = season.number
                                }
                                .buttonStyle(.bordered)
                                .tint(season.number == currentSeason?.number ? Color.accentColor : nil)
                            }
                        }
                        .padding(.vertical, 20)
                    }
                }

                List(currentSeason?.episodes ?? []) { episode in
                    Button {
                        play(episode)
                    } label: {
                        HStack(spacing: 24) {
                            Text("\(episode.episodeNumber ?? 0)")
                                .font(.callout.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(width: 60, alignment: .trailing)
                            VStack(alignment: .leading, spacing: 6) {
                                Text(episode.title)
                                    .lineLimit(1)
                                if let plot = episode.plot {
                                    Text(plot)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                            Spacer()
                            if let seconds = episode.durationSeconds, seconds > 0 {
                                Text("\(seconds / 60) min")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .overlay { LoadStateView(state: state, emptyText: "No episodes listed") { Task { await load() } } }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 40)
        .task { await load() }
        .playerCover(item: $nowPlaying)
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
        nowPlaying = PlayableItem(
            id: "episode-\(episode.id)",
            title: series.name,
            subtitle: label.isEmpty ? episode.title : "\(label) · \(episode.title)",
            url: client.episodeURL(episode),
            isLive: false
        )
    }
}
