import HillTVCore
import Observation
import SwiftUI

@MainActor
@Observable
final class LiveTVModel {
    let client: XtreamClient

    private(set) var categories: [XtreamCategory] = []
    private(set) var categoryState: LoadState = .idle
    private(set) var selectedCategoryID: String?
    private(set) var streams: [LiveStream] = []
    private(set) var streamState: LoadState = .idle
    private(set) var focusedStreamID: Int?
    private(set) var epg: [Int: [EPGEntry]] = [:]

    private var streamCache: [String: [LiveStream]] = [:]
    private var selectTask: Task<Void, Never>?
    private var epgTask: Task<Void, Never>?

    init(client: XtreamClient) {
        self.client = client
    }

    var focusedStream: LiveStream? {
        guard let focusedStreamID else { return nil }
        return streams.first { $0.id == focusedStreamID }
    }

    func loadCategories() async {
        guard categories.isEmpty, categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.liveCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            if let first = categories.first {
                await select(first.id)
            }
        } catch {
            categoryState = .failed(error.localizedDescription)
        }
    }

    /// Called as focus moves through the category list.
    func scheduleSelect(_ categoryID: String) {
        selectTask?.cancel()
        selectTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            await self?.select(categoryID)
        }
    }

    func select(_ categoryID: String) async {
        if categoryID == selectedCategoryID, streamState == .loaded || streamState == .loading { return }
        selectedCategoryID = categoryID

        if let cached = streamCache[categoryID] {
            streams = cached
            streamState = cached.isEmpty ? .empty : .loaded
            return
        }

        streams = []
        streamState = .loading
        do {
            let result = try await client.liveStreams(categoryID: categoryID)
            streamCache[categoryID] = result
            // Ignore late results if the user already moved to another category.
            guard selectedCategoryID == categoryID else { return }
            streams = result
            streamState = result.isEmpty ? .empty : .loaded
        } catch {
            guard selectedCategoryID == categoryID else { return }
            streamState = .failed(error.localizedDescription)
        }
    }

    func retryStreams() {
        guard let id = selectedCategoryID else { return }
        streamState = .idle
        Task { await select(id) }
    }

    /// Fetches now/next for the focused channel after a short pause, so
    /// scrolling quickly through a list doesn't fire a request per row.
    func focusChanged(to streamID: Int?) {
        focusedStreamID = streamID
        epgTask?.cancel()
        guard let streamID, needsEPG(streamID) else { return }
        epgTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled, let self else { return }
            let entries = (try? await self.client.shortEPG(streamID: streamID)) ?? []
            self.epg[streamID] = entries
        }
    }

    private func needsEPG(_ streamID: Int) -> Bool {
        guard let entries = epg[streamID] else { return true }
        // Refetch once everything cached has finished airing.
        return !entries.contains { $0.end > Date() }
    }
}

struct LiveTVView: View {
    @Environment(AppModel.self) private var app
    @State private var model: LiveTVModel
    @State private var nowPlaying: PlayableItem?
    @FocusState private var focusedCategory: String?
    @FocusState private var focusedStream: Int?

    init(client: XtreamClient) {
        _model = State(initialValue: LiveTVModel(client: client))
    }

    var body: some View {
        HStack(alignment: .top, spacing: 40) {
            CategoryColumn(
                title: "Categories",
                categories: model.categories,
                selectedID: model.selectedCategoryID,
                focus: $focusedCategory
            ) { category in
                Task { await model.select(category.id) }
            }
            .frame(width: 440)
            .overlay { LoadStateView(state: model.categoryState, emptyText: "No live categories") }

            channelList
                .frame(maxWidth: .infinity)

            NowNextPanel(stream: model.focusedStream, entries: model.focusedStream.flatMap { model.epg[$0.id] })
                .frame(width: 520)
        }
        .task { await model.loadCategories() }
        .onChange(of: focusedCategory) { _, newValue in
            if let newValue { model.scheduleSelect(newValue) }
        }
        .onChange(of: focusedStream) { _, newValue in
            model.focusChanged(to: newValue)
        }
        .playerCover(item: $nowPlaying)
    }

    private var channelList: some View {
        List(model.streams) { stream in
            Button {
                play(stream)
            } label: {
                ChannelRow(stream: stream, isFavorite: app.favorites.contains(stream.id))
            }
            .focused($focusedStream, equals: stream.id)
            .contextMenu {
                Button(app.favorites.contains(stream.id) ? "Remove from Favorites" : "Add to Favorites") {
                    app.favorites.toggle(stream)
                }
            }
        }
        .overlay {
            LoadStateView(state: model.streamState, emptyText: "No channels in this category") {
                model.retryStreams()
            }
        }
    }

    private func play(_ stream: LiveStream) {
        let now = model.epg[stream.id]?.first { $0.isOnAir() }
        nowPlaying = PlayableItem(
            id: "live-\(stream.id)",
            title: stream.name,
            subtitle: now?.title,
            url: model.client.liveURL(streamID: stream.id, format: app.liveFormat),
            isLive: true
        )
    }
}

/// Right-hand panel: logo, what's on now with progress, and what's next.
struct NowNextPanel: View {
    let stream: LiveStream?
    let entries: [EPGEntry]?

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            if let stream {
                ChannelLogo(url: stream.iconURL, size: CGSize(width: 240, height: 140))
                Text(stream.name)
                    .font(.title3.bold())
                    .lineLimit(2)

                if let entries {
                    let upcoming = entries.filter { $0.end > Date() }
                    if let now = upcoming.first(where: { $0.isOnAir() }) {
                        programme(label: "NOW", entry: now, showProgress: true)
                    }
                    if let next = upcoming.first(where: { $0.start > Date() }) {
                        programme(label: "NEXT", entry: next, showProgress: false)
                    }
                    if upcoming.isEmpty {
                        Text("No guide data")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    ProgressView()
                }
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func programme(label: String, entry: EPGEntry, showProgress: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            Text(entry.title)
                .font(.headline)
                .lineLimit(2)
            Text("\(entry.start.shortTime) to \(entry.end.shortTime)")
                .font(.caption)
                .foregroundStyle(.secondary)
            if showProgress {
                ProgressView(value: entry.progress())
            }
            if let summary = entry.summary, showProgress {
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(4)
            }
        }
    }
}
