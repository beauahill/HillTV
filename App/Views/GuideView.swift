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
    private var waiting: [Int] = []
    private var queued: Set<Int> = []
    private var active = 0

    private static let maxConcurrent = 4
    private static let entriesPerChannel = 12

    init(client: XtreamClient) {
        self.client = client
    }

    // MARK: Categories and channels (same pattern as Live TV)

    func loadCategories() async {
        refreshWindowIfNeeded()
        if !categories.isEmpty {
            if channelState == .idle, let id = selectedCategoryID ?? categories.first?.id {
                await select(id)
            }
            return
        }
        guard categoryState != .loading else { return }
        categoryState = .loading
        do {
            categories = try await client.liveCategories()
            categoryState = categories.isEmpty ? .empty : .loaded
            if let first = categories.first {
                await select(first.id)
            }
        } catch {
            categoryState = Task.isCancelled ? .idle : .failed(error.localizedDescription)
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
        if current.start != window.start {
            window = current
        }
    }

    // MARK: Listings, fetched lazily per visible row

    func rowAppeared(_ streamID: Int) {
        guard listings[streamID] == nil, !queued.contains(streamID), appearTasks[streamID] == nil else { return }
        // Only fetch rows that stay visible, so fast scrolling doesn't flood the panel.
        appearTasks[streamID] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self else { return }
            self.appearTasks[streamID] = nil
            self.enqueue(streamID)
        }
    }

    func rowDisappeared(_ streamID: Int) {
        appearTasks[streamID]?.cancel()
        appearTasks[streamID] = nil
        if let index = waiting.firstIndex(of: streamID) {
            waiting.remove(at: index)
            queued.remove(streamID)
        }
    }

    private func enqueue(_ streamID: Int) {
        guard listings[streamID] == nil, !queued.contains(streamID) else { return }
        queued.insert(streamID)
        waiting.append(streamID)
        pump()
    }

    private func pump() {
        let client = client
        let limit = Self.entriesPerChannel
        while active < Self.maxConcurrent, let streamID = waiting.popLast() {
            active += 1
            Task { [weak self] in
                let entries = (try? await client.shortEPG(streamID: streamID, limit: limit)) ?? []
                guard let self else { return }
                self.listings[streamID] = entries
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
                categories: model.categories,
                selectedID: model.selectedCategoryID,
                focus: $focusedCategory
            ) { category in
                Task { await model.select(category.id) }
            }
            .frame(width: 320)
            .overlay { LoadStateView(state: model.categoryState, emptyText: "No live categories") }

            GeometryReader { geo in
                let timeline = max(geo.size.width - channelColumn, 200)
                VStack(alignment: .leading, spacing: 12) {
                    timeHeader(timeline: timeline)
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 6) {
                            ForEach(model.channels) { stream in
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
                LoadStateView(state: model.channelState, emptyText: "No channels in this category") {
                    model.retryChannels()
                }
            }
        }
        .task { await model.loadCategories() }
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
                Text(stream.name)
                    .font(.tvSmall.weight(.semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
            }
            .frame(width: channelColumn - 12, alignment: .leading)
            .padding(.trailing, 12)

            HStack(spacing: 0) {
                ForEach(model.window.cells(for: model.listings[stream.id] ?? [])) { cell in
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
            title: stream.name,
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
