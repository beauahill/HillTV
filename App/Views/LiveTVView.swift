import HillTVCore
import SwiftUI

/// Live TV: big pills for Favorites and the category groups, the selected
/// group's own categories as a smaller row, the channel list on the left
/// with what's on now in every row, and details for the focused channel on
/// the right. The model is in `App/Model/LiveTVModel.swift`.
struct LiveTVView: View {
    @Environment(AppModel.self) private var app
    let model: LiveTVModel
    let epg: EPGStore

    @State private var nowPlaying: PlayableItem?
    @State private var showingCategories = false
    @State private var focusTask: Task<Void, Never>?
    @State private var playTask: Task<Void, Never>?
    /// Channel whose preview is playing, once focus has rested on it.
    @State private var previewStreamID: Int?
    /// Channels whose preview failed this visit. They keep the still.
    @State private var failedPreviews: Set<Int> = []
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var focusedStream: Int?

    private var streams: [LiveStream] {
        model.showsFavorites ? app.favorites.channels : model.streams
    }

    private var listState: LoadState {
        if model.showsFavorites {
            return app.favorites.channels.isEmpty ? .empty : .loaded
        }
        if model.categories.isEmpty {
            return model.categoryState
        }
        return model.streamState
    }

    private var emptyText: String {
        if model.showsFavorites {
            return model.allHidden ? allCategoriesHiddenText : "No favorites yet. Hold Select on a channel to add it."
        }
        return model.categories.isEmpty ? "No live categories" : "No channels in this category"
    }

    private var detailStream: LiveStream? {
        let all = streams
        if let id = model.detailStreamID, let match = all.first(where: { $0.id == id }) {
            return match
        }
        return all.first
    }

    private var startWithFavorites: Bool {
        !app.favorites.channels.isEmpty
    }

    // MARK: Preview

    /// The preview holds a connection, so it only runs while it can be seen:
    /// not behind the player or the category grid, not in the background.
    private var previewAllowed: Bool {
        app.previewMode != .off && app.previewSupported
            && nowPlaying == nil && !showingCategories && scenePhase == .active
    }

    private struct PreviewKey: Hashable {
        let streamID: Int?
        let allowed: Bool
    }

    /// Only a channel that has actually held focus, never just the first in the list.
    private var previewKey: PreviewKey {
        PreviewKey(streamID: model.detailStreamID, allowed: previewAllowed)
    }

    private var previewURL: URL? {
        guard previewAllowed, let id = previewStreamID, id == detailStream?.id else { return nil }
        return model.client.liveURL(streamID: id, format: .hls)
    }

    /// Restarted by `.task(id:)` whenever the channel or `previewAllowed`
    /// changes, which cancels the previous run. Stops the old preview at
    /// once, starts the new one after a pause, and gives up the connection
    /// if the same channel sits there for a few minutes.
    private func runPreview() async {
        previewStreamID = nil
        guard previewAllowed, let id = model.detailStreamID, !failedPreviews.contains(id) else { return }
        try? await Task.sleep(for: .seconds(1))
        guard !Task.isCancelled else { return }
        previewStreamID = id
        try? await Task.sleep(for: .seconds(180))
        guard !Task.isCancelled else { return }
        previewStreamID = nil
    }

    private func previewFailed(_ streamID: Int) {
        failedPreviews.insert(streamID)
        if previewStreamID == streamID { previewStreamID = nil }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            pillBar
                .focusSection()

            if !model.subcategories.isEmpty {
                categoryBar
                    .focusSection()
            }

            HStack(alignment: .top, spacing: 32) {
                channelList
                    .frame(width: 760)
                    .focusSection()

                ChannelDetailPane(
                    stream: detailStream,
                    epg: epg,
                    previewURL: previewURL,
                    previewMuted: app.previewMode != .sound,
                    onPreviewFailure: { id in previewFailed(id) }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
        }
        .task { await model.loadCategories(startWithFavorites: startWithFavorites) }
        .task(id: previewKey) { await runPreview() }
        .onChange(of: focusedStream) { _, newValue in
            focusChanged(to: newValue)
        }
        .playerCover(item: $nowPlaying)
    }

    // MARK: Pills and categories

    private var pills: [LiveTVModel.Pill] {
        [.favorites] + model.groups.map { .group($0.group) }
    }

    private var pillBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(pills, id: \.self) { pill in
                    Button {
                        Task { await model.select(pill) }
                    } label: {
                        Label(Self.title(pill), systemImage: Self.symbol(pill))
                    }
                    .buttonStyle(TVPillButtonStyle(isSelected: model.selection?.pill == pill))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 14)
        }
        // Focused pills scale past the scroll view's edge; don't cut them off.
        .scrollClipDisabled()
    }

    private static func title(_ pill: LiveTVModel.Pill) -> String {
        switch pill {
        case .favorites: return "Favorites"
        case .group(let group): return group.title
        }
    }

    private static func symbol(_ pill: LiveTVModel.Pill) -> String {
        switch pill {
        case .favorites: return "heart.fill"
        case .group(.sports): return "sportscourt"
        case .group(.news): return "newspaper"
        case .group(.movies): return "film"
        case .group(.shows): return "tv"
        case .group(.kids): return "face.smiling"
        case .group(.music): return "music.note"
        case .group(.world): return "globe"
        case .group(.other): return "square.grid.2x2"
        }
    }

    /// The selected group's own categories, with "All" first when the group
    /// is small enough to merge and a grid button when it is long.
    private var categoryBar: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    if model.subcategories.count > 8 {
                        Button {
                            showingCategories = true
                        } label: {
                            Image(systemName: "square.grid.2x2")
                        }
                        .buttonStyle(TVChipButtonStyle())
                    }

                    if model.offersAll {
                        chip("All", id: LiveTVModel.allID)
                    }

                    ForEach(model.subcategories) { category in
                        chip(category.name, id: category.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
            }
            // Focused chips scale past the scroll view's edge; don't cut them off.
            .scrollClipDisabled()
            .frame(height: 76)
            .onChange(of: model.selection) { _, newValue in
                // A pick from the grid may be far along the row.
                guard let sub = newValue?.sub else { return }
                withAnimation { proxy.scrollTo(sub, anchor: .center) }
            }
        }
        .fullScreenCover(isPresented: $showingCategories) {
            CategoryPicker(
                title: model.selection.map { Self.title($0.pill) } ?? "Live TV",
                categories: model.subcategories,
                selectedID: model.selection?.sub
            ) { id in
                Task { await model.select(sub: id) }
            }
        }
    }

    private func chip(_ title: String, id: String) -> some View {
        Button {
            Task { await model.select(sub: id) }
        } label: {
            Text(title)
        }
        .buttonStyle(TVChipButtonStyle(isSelected: model.selection?.sub == id))
        .id(id)
    }

    // MARK: Channels

    private var channelList: some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: 4) {
                ForEach(streams) { stream in
                    Button {
                        play(stream)
                    } label: {
                        LiveChannelRow(
                            stream: stream,
                            epg: epg,
                            isFavorite: !model.showsFavorites && app.favorites.contains(stream.id)
                        )
                    }
                    .buttonStyle(TVRowButtonStyle(minHeight: Theme.listRowHeight))
                    .focused($focusedStream, equals: stream.id)
                    .contextMenu {
                        Button(app.favorites.contains(stream.id) ? "Remove from Favorites" : "Add to Favorites") {
                            app.favorites.toggle(stream)
                        }
                    }
                    .onAppear { epg.rowAppeared(stream.id) }
                    .onDisappear { epg.rowDisappeared(stream.id) }
                }
            }
            .padding(12)
        }
        .overlay {
            LoadStateView(state: listState, emptyText: emptyText) {
                retry()
            }
        }
    }

    private func retry() {
        if model.categories.isEmpty {
            model.retryCategories(startWithFavorites: startWithFavorites)
        } else {
            model.retryStreams()
        }
    }

    /// Waits for focus to settle before updating the detail pane, so holding
    /// down on the remote doesn't redraw it for every row passed.
    private func focusChanged(to streamID: Int?) {
        focusTask?.cancel()
        guard let streamID else { return }
        let model = model
        let epg = epg
        focusTask = Task {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            model.showDetail(for: streamID)
            epg.prioritize(streamID)
        }
    }

    private func play(_ stream: LiveStream) {
        let current = epg.schedule(for: stream.id)?.current(at: Date())
        let item = PlayableItem(
            id: "live-\(stream.id)",
            title: stream.name,
            subtitle: current?.title,
            url: model.client.liveURL(streamID: stream.id, format: app.liveFormat),
            isLive: true
        )
        playTask?.cancel()
        guard previewStreamID != nil, !app.hasSpareConnection else {
            nowPlaying = item
            return
        }
        // One connection and a preview holding it: let go first, then open the player.
        previewStreamID = nil
        playTask = Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            nowPlaying = item
        }
    }
}

/// One channel: number, logo, name, what's on now and how far along it is.
private struct LiveChannelRow: View {
    let stream: LiveStream
    let epg: EPGStore
    let isFavorite: Bool

    var body: some View {
        let now = epg.now
        let schedule = epg.schedule(for: stream.id)
        let current = schedule?.current(at: now)

        HStack(spacing: 18) {
            if let number = stream.number {
                Text("\(number)")
                    .font(.tvSmall.monospacedDigit())
                    .tvSecondary()
                    .frame(width: 60, alignment: .trailing)
            }
            ChannelLogo(url: stream.iconURL)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 10) {
                    Text(stream.name)
                        .font(.tvMeta.weight(.semibold))
                        .lineLimit(1)
                    if isFavorite {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 18))
                    }
                }
                Text(subtitle(schedule: schedule, current: current, now: now))
                    .font(.tvSmall)
                    .tvSecondary()
                    .lineLimit(1)
            }
            Spacer(minLength: 12)
            if let current {
                VStack(alignment: .trailing, spacing: 8) {
                    Text(remainingLabel(minutes: EPGSchedule.minutesLeft(in: current, at: now)))
                        .font(.tvSmall.monospacedDigit())
                        .tvSecondary()
                        .lineLimit(1)
                    FocusAwareProgressBar(value: current.progress(at: now))
                        .frame(width: 130)
                }
            }
        }
    }

    private func subtitle(schedule: EPGSchedule?, current: EPGEntry?, now: Date) -> String {
        // Not fetched yet: keep the line's height without saying anything.
        guard let schedule else { return " " }
        if let current { return current.title }
        if let next = schedule.next(after: now) {
            return "Next at \(next.start.shortTime): \(next.title)"
        }
        return "No guide data"
    }
}

/// Right-hand side: a still or live preview of the focused channel, and the next few hours.
private struct ChannelDetailPane: View {
    let stream: LiveStream?
    let epg: EPGStore
    let previewURL: URL?
    let previewMuted: Bool
    let onPreviewFailure: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if let stream {
                let now = epg.now
                let schedule = epg.schedule(for: stream.id)

                ChannelStill(
                    stream: stream,
                    current: schedule?.current(at: now),
                    previewURL: previewURL,
                    previewMuted: previewMuted,
                    onPreviewFailure: { onPreviewFailure(stream.id) }
                )
                .frame(maxHeight: 440)

                HStack(spacing: 16) {
                    if let number = stream.number {
                        Text("\(number)")
                            .font(.tvHeading.monospacedDigit())
                            .foregroundStyle(Theme.muted)
                    }
                    Text(stream.name)
                        .font(.tvSubtitle)
                        .foregroundStyle(Theme.text)
                        .lineLimit(1)
                    if stream.hasArchive {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 26))
                            .foregroundStyle(Theme.muted)
                    }
                }

                timeline(schedule, now: now)
            } else {
                Eyebrow(text: "Live TV")
                Text("Pick a channel to see what's on.")
                    .font(.tvMeta)
                    .foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 12)
    }

    @ViewBuilder
    private func timeline(_ schedule: EPGSchedule?, now: Date) -> some View {
        if let schedule {
            let upcoming = schedule.upcoming(from: now, limit: 4)
            if upcoming.isEmpty {
                Text("No guide data for this channel.")
                    .font(.tvMeta)
                    .foregroundStyle(Theme.muted)
            } else {
                VStack(spacing: 6) {
                    ForEach(upcoming) { entry in
                        TimelineRow(entry: entry, now: now)
                    }
                }
            }
        } else {
            ProgressView()
                .tint(Theme.accent)
        }
    }
}

/// Logo card standing in for the picture, with what's on along the bottom.
/// When a preview URL is set, the live picture plays over the logo.
private struct ChannelStill: View {
    let stream: LiveStream
    let current: EPGEntry?
    let previewURL: URL?
    let previewMuted: Bool
    let onPreviewFailure: () -> Void

    var body: some View {
        ZStack {
            Theme.surface
            ChannelLogo(url: stream.iconURL, size: CGSize(width: 320, height: 200))
            if let previewURL {
                LivePreviewView(
                    url: previewURL,
                    userAgent: AppModel.userAgent,
                    muted: previewMuted,
                    onFailure: onPreviewFailure
                )
                // One player per channel: a new URL tears the old one down.
                .id(previewURL)
            }
        }
        .aspectRatio(16.0 / 9.0, contentMode: .fit)
        .overlay(alignment: .topLeading) {
            HStack(spacing: 10) {
                Circle()
                    .fill(Theme.live)
                    .frame(width: 12, height: 12)
                Text("Live")
                    .font(.tvSmall.weight(.semibold))
                    .foregroundStyle(Theme.text)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Theme.background.opacity(0.7), in: Capsule())
            .padding(20)
        }
        .overlay(alignment: .bottomLeading) {
            if let current {
                VStack(alignment: .leading, spacing: 6) {
                    Text(current.title)
                        .font(.tvBody.weight(.semibold))
                        .foregroundStyle(Theme.text)
                        .lineLimit(1)
                    if let summary = current.summary, !summary.isEmpty {
                        Text(summary)
                            .font(.tvSmall)
                            .foregroundStyle(Theme.muted)
                            .lineLimit(2)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.background.opacity(0.78))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
    }
}

private struct TimelineRow: View {
    let entry: EPGEntry
    let now: Date

    var body: some View {
        let onAir = entry.isOnAir(at: now)

        HStack(spacing: 20) {
            Text(entry.start.shortTime)
                .font(.tvSmall.monospacedDigit())
                .foregroundStyle(Theme.muted)
                .frame(width: 120, alignment: .leading)
            Text(entry.title)
                .font(.tvMeta.weight(onAir ? .semibold : .regular))
                .foregroundStyle(onAir ? Theme.text : Theme.muted)
                .lineLimit(1)
            Spacer(minLength: 12)
            if onAir {
                ProgressBar(value: entry.progress(at: now))
                    .frame(width: 160)
                Text(remainingLabel(minutes: EPGSchedule.minutesLeft(in: entry, at: now)))
                    .font(.tvSmall.monospacedDigit())
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 56)
        .background {
            if onAir {
                RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                    .fill(Theme.surface)
            }
        }
    }
}

/// One group's categories in a grid, for groups with far more than fit in the chip row.
private struct CategoryPicker: View {
    let title: String
    let categories: [XtreamCategory]
    let selectedID: String?
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 16, alignment: .top), count: 4)

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Live TV")
                    Text(title)
                        .font(.tvTitle)
                        .foregroundStyle(Theme.text)
                }
                .padding(.leading, 36)

                ScrollView(.vertical) {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                        ForEach(categories) { category in
                            Button {
                                onPick(category.id)
                                dismiss()
                            } label: {
                                Text(category.name)
                                    .lineLimit(1)
                            }
                            .buttonStyle(TVRowButtonStyle(isSelected: category.id == selectedID, minHeight: 64, font: .tvMeta))
                        }
                    }
                    .padding(12)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
