import HillTVCore
import Observation
import SwiftUI

/// TV guide: categories on the left, a timeline grid of channels on the
/// right. Listings come from the panel's short EPG, fetched per row once it
/// has stayed on screen for a moment, a few at a time, and cached. The full
/// XMLTV guide (often 10-100 MB) isn't downloaded yet.
@MainActor
@Observable
final class GuideModel {
    let client: XtreamClient

    private(set) var categories: [XtreamCategory] = []
    private(set) var categoryState: LoadState = .idle
    private(set) var selectedCategoryID: String?
    private(set) var channels: [LiveStream] = []
    private(set) var channelState: LoadState = .idle
    private(set) var listings: [Int: [EPGEntry]] = [:]
    private(set) var window = GuideWindow.starting(at: Date(), hours: 3)

    private var channelCache: [String: [LiveStream]] = [:]
    private var selectTask: Task<Void, Never>?
    private var appearTasks: [Int: Task<Void, Never>] = [:]
    /// Window start each channel's listings were fetched for. Older means stale.
    private var fetchedFor: [Int: Date] = [:]
    /// Rows on screen now, so a window change can refresh them.
    private var visible: Set<Int> = []
    private var waiting: [Int] = []
    private var queued: Set<Int> = []
    private var active = 0

    private static let maxConcurrent = 4
    private static let entriesPerChannel = 12
    private let visibility: CategoryVisibilityStore

    /// Stands in for a category ID when the Guide shows favorite channels.
    static let favoritesID = "hilltv.favorites"

    /// True when the Guide lists favorites. The view reads those channels
    /// from `FavoritesStore`, so adding or removing one shows at once.
    var showsFavorites: Bool {
        selectedCategoryID == Self.favoritesID
    }

    init(client: XtreamClient, visibility: CategoryVisibilityStore) {
        self.client = client
        self.visibility = visibility
    }

    /// Categories the user hasn't hidden.
    var visibleCategories: [XtreamCategory] {
        visibility.visible(categories, in: .live)
    }

    var allHidden: Bool {
        !categories.isEmpty && visibleCategories.isEmpty
    }

    // MARK: Categories and channels (same pattern as Live TV)

    func loadCategories(startWithFavorites: Bool) async {
        refreshWindowIfNeeded()
        if selectedCategoryID == nil, startWithFavorites {
            await select(Self.favoritesID)
        }
        if !categories.isEmpty {
            await reconcileSelection()
            return
        }
        guard categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.liveCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            await reconcileSelection()
        } catch {
            categoryState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    /// Keeps the selection on a category that is still shown, since categories
    /// can be hidden in Settings between visits. Also refetches after a
    /// cancelled first load. Favorites is never hidden, and it is where the
    /// Guide goes when every category is.
    private func reconcileSelection() async {
        if showsFavorites { return }
        let visible = visibleCategories
        if let id = selectedCategoryID, visible.contains(where: { $0.id == id }) {
            if channelState == .idle { await select(id) }
        } else if let first = visible.first {
            await select(first.id)
        } else {
            await select(Self.favoritesID)
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
        if categoryID == selectedCategoryID, channelState == .loaded || channelState == .loading { return }
        selectedCategoryID = categoryID
        if categoryID == Self.favoritesID {
            // Nothing to fetch: the view lists the channels from FavoritesStore.
            channels = []
            channelState = .loaded
            return
        }
        if let cached = channelCache[categoryID] {
            channels = cached
            channelState = cached.isEmpty ? .empty : .loaded
            return
        }
        channels = []
        channelState = .loading
        do {
            let result = try await client.liveStreams(categoryID: categoryID)
            channelCache[categoryID] = result
            guard selectedCategoryID == categoryID else { return }
            channels = result
            channelState = result.isEmpty ? .empty : .loaded
        } catch {
            guard selectedCategoryID == categoryID else { return }
            channelState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
        }
    }

    func retryChannels() {
        guard let id = selectedCategoryID else { return }
        channelState = .idle
        Task { await select(id) }
    }

    /// Moves the window forward once the current half hour has passed.
    func refreshWindowIfNeeded() {
        let current = GuideWindow.starting(at: Date(), hours: 3)
        guard current.start != window.start else { return }
        window = current
        // tvOS keeps apps around for days; refetch what's on screen for the new window.
        for streamID in visible where needsFetch(streamID) {
            enqueue(streamID)
        }
    }

    // MARK: Listings, fetched lazily per visible row

    private func needsFetch(_ streamID: Int) -> Bool {
        fetchedFor[streamID] != window.start
    }

    func rowAppeared(_ streamID: Int) {
        visible.insert(streamID)
        guard needsFetch(streamID), !queued.contains(streamID), appearTasks[streamID] == nil else { return }
        // Only fetch rows that stay visible, so fast scrolling doesn't flood the panel.
        appearTasks[streamID] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self else { return }
            self.appearTasks[streamID] = nil
            self.enqueue(streamID)
        }
    }

    func rowDisappeared(_ streamID: Int) {
        visible.remove(streamID)
        appearTasks[streamID]?.cancel()
        appearTasks[streamID] = nil
        if let index = waiting.firstIndex(of: streamID) {
            waiting.remove(at: index)
            queued.remove(streamID)
        }
    }

    private func enqueue(_ streamID: Int) {
        guard needsFetch(streamID), !queued.contains(streamID) else { return }
        queued.insert(streamID)
        waiting.append(streamID)
        pump()
    }

    private func pump() {
        let client = client
        let limit = Self.entriesPerChannel
        while active < Self.maxConcurrent, let streamID = waiting.popLast() {
            active += 1
            let windowStart = window.start
            Task { [weak self] in
                let entries: [EPGEntry]?
                do {
                    entries = try await client.shortEPG(streamID: streamID, limit: limit)
                } catch {
                    entries = nil
                }
                guard let self else { return }
                if let entries {
                    self.listings[streamID] = entries
                    self.fetchedFor[streamID] = windowStart
                } else if self.listings[streamID] == nil {
                    // Failed: show the empty state, but don't mark it fetched,
                    // so the row tries again next time it comes on screen.
                    self.listings[streamID] = []
                }
                self.queued.remove(streamID)
                self.active -= 1
                self.pump()
            }
        }
    }
}

struct GuideView: View {
    let model: GuideModel

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?
    @FocusState private var focusedCategory: String?

    private let channelColumn: CGFloat = 250
    private let rowHeight: CGFloat = 88

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            CategoryColumn(
                title: "Guide",
                categories: [favoritesEntry] + model.visibleCategories,
                selectedID: model.selectedCategoryID,
                focus: $focusedCategory
            ) { category in
                Task { await model.select(category.id) }
            }
            .frame(width: 320)
            .overlay {
                LoadStateView(state: model.categoryState, emptyText: "No live categories")
            }

            GeometryReader { geo in
                let timeline = max(geo.size.width - channelColumn, 200)
                VStack(alignment: .leading, spacing: 12) {
                    timeHeader(timeline: timeline)
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 6) {
                            ForEach(rows) { stream in
                                row(stream, timeline: timeline)
                                    .onAppear { model.rowAppeared(stream.id) }
                                    .onDisappear { model.rowDisappeared(stream.id) }
                            }
                        }
                        .padding(.vertical, 8)
                    }
                    .overlay(alignment: .topLeading) {
                        nowLine(timeline: timeline)
                    }
                }
            }
            .overlay {
                LoadStateView(state: channelState, emptyText: emptyText) {
                    model.retryChannels()
                }
            }
        }
        .task { await model.loadCategories(startWithFavorites: !app.favorites.channels.isEmpty) }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                model.refreshWindowIfNeeded()
            }
        }
        .onChange(of: focusedCategory) { _, newValue in
            if let newValue { model.scheduleSelect(newValue) }
        }
        .playerCover(item: $nowPlaying)
    }

    /// "Favorites" sits above the provider's categories and is never hidden.
    private var favoritesEntry: XtreamCategory {
        XtreamCategory(id: GuideModel.favoritesID, name: "Favorites")
    }

    private var rows: [LiveStream] {
        model.showsFavorites ? app.favorites.channels : model.channels
    }

    private var channelState: LoadState {
        guard model.showsFavorites else { return model.channelState }
        return app.favorites.channels.isEmpty ? .empty : .loaded
    }

    private var emptyText: String {
        guard model.showsFavorites else { return "No channels in this category" }
        return model.allHidden ? allCategoriesHiddenText : "No favorites yet. Hold Select on a channel to add it."
    }

    private func timeHeader(timeline: CGFloat) -> some View {
        ZStack(alignment: .leading) {
            ForEach(model.window.slots(), id: \.self) { slot in
                Text(slot.shortTime)
                    .font(.tvSmall.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                    .offset(x: channelColumn + model.window.fraction(of: slot) * timeline + 8)
            }
        }
        .frame(height: 32, alignment: .leading)
    }

    private func nowLine(timeline: CGFloat) -> some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Rectangle()
                .fill(Theme.live)
                .frame(width: 3)
                .offset(x: channelColumn + model.window.fraction(of: context.date) * timeline)
                .allowsHitTesting(false)
        }
    }

    private func row(_ stream: LiveStream, timeline: CGFloat) -> some View {
        HStack(spacing: 0) {
            HStack(spacing: 14) {
                ChannelLogo(url: stream.iconURL, size: CGSize(width: 80, height: 52))
                ChannelNameLabel(
                    name: stream.displayName,
                    font: .tvSmall.weight(.semibold),
                    detailFont: .system(size: 18),
                    titleLines: 2
                )
                .foregroundStyle(Theme.text)
            }
            .frame(width: channelColumn - 12, alignment: .leading)
            .padding(.trailing, 12)

            HStack(spacing: 0) {
                // Positional IDs: when listings arrive, the focused first cell stays the same view.
                let cells = model.window.cells(for: model.listings[stream.id] ?? [])
                ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                    Button {
                        play(stream, now: cell.entry)
                    } label: {
                        GuideCellLabel(cell: cell, isLoading: model.listings[stream.id] == nil)
                    }
                    .buttonStyle(GuideCellButtonStyle(isOnAir: cell.entry?.isOnAir() ?? false, isGap: cell.entry == nil))
                    .frame(width: cell.width * timeline, height: rowHeight)
                    .contextMenu {
                        Button(app.favorites.contains(stream.id) ? "Remove from Favorites" : "Add to Favorites") {
                            app.favorites.toggle(stream)
                        }
                    }
                }
            }
            .frame(width: timeline, alignment: .leading)
        }
        .frame(height: rowHeight)
    }

    private func play(_ stream: LiveStream, now entry: EPGEntry?) {
        let onAir = model.listings[stream.id]?.first { $0.isOnAir() }
        nowPlaying = PlayableItem(
            id: "live-\(stream.id)",
            title: stream.displayName.title,
            subtitle: onAir?.title ?? entry?.title,
            url: model.client.liveURL(streamID: stream.id, format: app.liveFormat),
            isLive: true
        )
    }
}

private struct GuideCellLabel: View {
    let cell: GuideWindow.Cell
    let isLoading: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let entry = cell.entry {
                Text(entry.title)
                    .font(.tvSmall.weight(.semibold))
                    .lineLimit(1)
                Text("\(entry.start.shortTime) to \(entry.end.shortTime)")
                    .font(.system(size: 18))
                    .tvSecondary()
                    .lineLimit(1)
            } else {
                Text(isLoading ? "Loading" : "No guide data")
                    .font(.tvSmall)
                    .tvSecondary()
                    .lineLimit(1)
            }
        }
    }
}

/// Guide cells show focus with a fill only: no scale and no ring, so
/// neighbouring cells and row edges never clip or overlap.
private struct GuideCellButtonStyle: ButtonStyle {
    let isOnAir: Bool
    let isGap: Bool

    func makeBody(configuration: Configuration) -> some View {
        CellBody(configuration: configuration, isOnAir: isOnAir, isGap: isGap)
    }

    private struct CellBody: View {
        let configuration: ButtonStyleConfiguration
        let isOnAir: Bool
        let isGap: Bool
        @Environment(\.isFocused) private var isFocused

        private var fill: Color {
            if isFocused { return Theme.accent }
            if isGap { return Theme.surface }
            return isOnAir ? Theme.controlRaised : Theme.control
        }

        var body: some View {
            configuration.label
                .foregroundStyle(isFocused ? Theme.onAccent : Theme.text)
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(fill)
                        .padding(3)
                }
                .clipped()
                .opacity(configuration.isPressed ? 0.85 : 1)
                .animation(Theme.focusAnimation, value: isFocused)
        }
    }
}
