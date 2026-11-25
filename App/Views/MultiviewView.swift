import HillTVCore
import SwiftUI

/// Several live channels on screen at once, in an even grid: two side by
/// side, three or four as two by two.
///
/// Sound follows focus. Select shows the focused channel full screen and
/// Select or Menu brings the grid back; the other channels keep playing
/// underneath, so nothing reconnects. Hold Select opens a menu with Remove.
///
/// Every tile is its own connection to the provider, so the account's
/// connection count caps the number of tiles (`AppModel.multiviewLimit`).
/// Tiles are always AVPlayer with the HLS URL, like the Live TV preview, and
/// each asks for a picture no bigger than it is shown
/// (`Multiview.pictureCap`).
struct MultiviewView: View {
    let client: XtreamClient
    let epg: EPGStore

    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase

    private enum Cell: Hashable {
        case tile(Int)
        case add
    }

    @FocusState private var focus: Cell?
    /// The channel with sound: the last tile that held focus.
    @State private var soundID: Int?
    /// The channel shown full screen, if one is.
    @State private var fullScreenID: Int?
    /// True while the list of channels to add is open.
    @State private var picking = false
    /// Why a channel isn't playing, by stream ID.
    @State private var errors: [Int: String] = [:]
    /// Bumped to give a failed channel a new player.
    @State private var attempts: [Int: Int] = [:]
    /// False for a moment after opening, while Live TV's preview lets go of its connection.
    @State private var ready = false
    @State private var showsHint = true

    private static let spacing: CGFloat = 12

    private var tiles: [LiveStream] { app.multiview.tiles.streams }
    private var canAdd: Bool { tiles.count < app.multiviewLimit }
    private var soundStreamID: Int? { soundID ?? tiles.first?.id }
    private var isPlaying: Bool { ready && scenePhase == .active }

    /// Why the multiview can't work on this account, if it can't.
    private var unavailableReason: String? {
        if !app.previewSupported {
            return "Multiview plays channels as HLS, which this provider doesn't offer."
        }
        if app.multiviewLimit < 2 {
            return "This account allows one connection at a time, and every channel on screen is a connection. Multiview needs an account with two or more."
        }
        return nil
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let reason = unavailableReason {
                unavailable(reason)
            } else {
                grid
                    .ignoresSafeArea()
                    .disabled(picking)
                overlays
                if picking {
                    picker
                }
            }
        }
        // With this set, Menu no longer closes the cover by itself.
        .onExitCommand(perform: back)
        .onChange(of: focus) { _, cell in
            if case .tile(let id)? = cell { soundID = id }
        }
        .onChange(of: tiles) { _, current in
            let ids = Set(current.map(\.id))
            if let id = fullScreenID, !ids.contains(id) { fullScreenID = nil }
            if let id = soundID, !ids.contains(id) { soundID = nil }
        }
        .task {
            // The preview in Live TV stops when this opens, and a panel can
            // take a moment to free that connection.
            if app.previewMode != .off {
                try? await Task.sleep(for: .milliseconds(700))
            }
            ready = true
            try? await Task.sleep(for: .seconds(7))
            showsHint = false
        }
    }

    private func back() {
        if picking {
            picking = false
        } else if fullScreenID != nil {
            fullScreenID = nil
        } else {
            app.multiview.isShowing = false
        }
    }

    // MARK: Grid

    private var grid: some View {
        MultiviewLayout(fullScreenIndex: tiles.firstIndex { $0.id == fullScreenID }, spacing: Self.spacing) {
            ForEach(tiles) { stream in
                tileButton(stream)
            }
            if canAdd {
                Button {
                    picking = true
                } label: {
                    AddTile()
                }
                .buttonStyle(TVCardButtonStyle())
                .focused($focus, equals: .add)
                .opacity(fullScreenID == nil ? 1 : 0)
                .disabled(fullScreenID != nil)
            }
        }
        .padding(fullScreenID == nil ? Self.spacing : 0)
        .animation(.easeInOut(duration: 0.25), value: fullScreenID)
        .animation(.easeInOut(duration: 0.25), value: tiles.count)
    }

    private func tileButton(_ stream: LiveStream) -> some View {
        let isFullScreen = fullScreenID == stream.id
        let hidden = fullScreenID != nil && !isFullScreen
        let cap = Multiview.pictureCap(tiles: tiles.count, fullScreen: isFullScreen)
        return Button {
            if errors[stream.id] != nil {
                retry(stream.id)
            } else {
                fullScreenID = isFullScreen ? nil : stream.id
            }
        } label: {
            MultiviewTile(
                stream: stream,
                url: isPlaying ? client.liveURL(streamID: stream.id, format: .hls) : nil,
                attempt: attempts[stream.id] ?? 0,
                muted: stream.id != soundStreamID,
                maxResolution: cap.map { CGSize(width: $0.width, height: $0.height) },
                nowTitle: epg.schedule(for: stream.id)?.current(at: epg.now)?.title,
                error: errors[stream.id],
                isFullScreen: isFullScreen,
                onError: { reason in errors[stream.id] = reason }
            )
        }
        .buttonStyle(TVCardButtonStyle())
        .focused($focus, equals: .tile(stream.id))
        .contextMenu {
            Button(isFullScreen ? "Back to the grid" : "Full screen") {
                fullScreenID = isFullScreen ? nil : stream.id
            }
            Button("Remove", role: .destructive) {
                remove(stream.id)
            }
            Button("Close Multiview") {
                app.multiview.isShowing = false
            }
        }
        // Covered tiles keep playing, but can't take focus.
        .opacity(hidden ? 0 : 1)
        .disabled(hidden)
        .zIndex(isFullScreen ? 1 : 0)
        .onAppear { epg.rowAppeared(stream.id) }
        .onDisappear { epg.rowDisappeared(stream.id) }
    }

    private func retry(_ streamID: Int) {
        errors[streamID] = nil
        attempts[streamID, default: 0] += 1
    }

    private func remove(_ streamID: Int) {
        errors[streamID] = nil
        attempts[streamID] = nil
        app.multiview.remove(streamID)
    }

    // MARK: Messages over the grid

    @ViewBuilder
    private var overlays: some View {
        VStack {
            if let notice = app.multiview.notice {
                Banner(text: notice)
            }
            Spacer()
            if showsHint, fullScreenID == nil, !picking, !tiles.isEmpty {
                Banner(text: "Select: full screen. Hold Select: more. Menu: close.")
            }
        }
        .padding(48)
        .allowsHitTesting(false)
    }

    private func unavailable(_ reason: String) -> some View {
        VStack(spacing: 28) {
            Image(systemName: "rectangle.split.2x2")
                .font(.system(size: 84))
                .foregroundStyle(Theme.muted)
            Text("Multiview isn't available")
                .font(.tvTitle)
                .foregroundStyle(Theme.text)
            Text(reason)
                .font(.tvMeta)
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 1000)
            Button("Close") {
                app.multiview.isShowing = false
            }
            .buttonStyle(TVActionButtonStyle(prominent: true))
        }
        .padding(80)
    }

    // MARK: Adding a channel

    /// Favorites that aren't on screen yet. Any other channel is added from
    /// its hold-Select menu elsewhere in the app.
    private var candidates: [LiveStream] {
        app.favorites.channels.filter { !app.multiview.tiles.contains($0.id) }
    }

    private var picker: some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Add a channel")
                    Text("Favorites")
                        .font(.tvTitle)
                        .foregroundStyle(Theme.text)
                }
                .padding(.leading, 24)

                if candidates.isEmpty {
                    Text(app.favorites.channels.isEmpty ? "No favorites yet." : "Every favorite is already on screen.")
                        .font(.tvMeta)
                        .foregroundStyle(Theme.muted)
                        .padding(.leading, 24)
                    Button("Close") {
                        picking = false
                    }
                    .buttonStyle(TVActionButtonStyle(prominent: true))
                    .padding(.leading, 24)
                    Spacer(minLength: 0)
                } else {
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            ForEach(candidates) { stream in
                                Button {
                                    add(stream)
                                } label: {
                                    ChannelRow(stream: stream, isFavorite: false)
                                }
                                .buttonStyle(TVRowButtonStyle(minHeight: Theme.listRowHeight))
                            }
                        }
                        .padding(12)
                    }
                }

                Text("For any other channel, hold Select on it in Live TV, the Guide or Favorites and pick Add to Multiview.")
                    .font(.tvSmall)
                    .foregroundStyle(Theme.muted)
                    .padding(.leading, 24)
            }
            .padding(.vertical, 60)
            .padding(.horizontal, 40)
            .frame(width: 860)
            .frame(maxHeight: .infinity, alignment: .topLeading)
            .background(Theme.background.ignoresSafeArea())
        }
    }

    private func add(_ stream: LiveStream) {
        app.multiview.add(stream, limit: app.multiviewLimit)
        picking = false
        Task { @MainActor in
            // The new tile has to exist before it can take focus.
            try? await Task.sleep(for: .milliseconds(300))
            focus = .tile(stream.id)
        }
    }
}

/// Places the cells in `Multiview.frames`, and the full-screen one, if any,
/// over the whole area. A real layout rather than offsets, so the focus
/// engine sees each tile where it is drawn.
private struct MultiviewLayout: Layout {
    var fullScreenIndex: Int?
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let frames = Multiview.frames(
            cells: subviews.count,
            width: Double(bounds.width),
            height: Double(bounds.height),
            spacing: Double(spacing)
        )
        for (index, subview) in subviews.enumerated() {
            if index == fullScreenIndex {
                subview.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
            } else if index < frames.count {
                let frame = frames[index]
                subview.place(
                    at: CGPoint(x: bounds.minX + frame.x, y: bounds.minY + frame.y),
                    proposal: ProposedViewSize(width: frame.width, height: frame.height)
                )
            }
        }
    }
}

/// One channel: its logo until the picture starts, then the picture.
private struct MultiviewTile: View {
    let stream: LiveStream
    /// Nil while nothing should play: just opened, or the app is in the background.
    let url: URL?
    let attempt: Int
    let muted: Bool
    let maxResolution: CGSize?
    let nowTitle: String?
    let error: String?
    let isFullScreen: Bool
    let onError: (String) -> Void

    @Environment(\.isFocused) private var isFocused

    private var corner: CGFloat { isFullScreen ? 0 : 12 }

    var body: some View {
        ZStack {
            Theme.surface
            ChannelLogo(url: stream.iconURL, size: CGSize(width: 240, height: 150))
            if let url, error == nil {
                LivePreviewView(
                    url: url,
                    userAgent: AppModel.userAgent,
                    muted: muted,
                    maxResolution: maxResolution,
                    keepsDisplayAwake: true,
                    onError: onError
                )
                // One player per channel and per try.
                .id("\(stream.id)-\(attempt)")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .topTrailing) {
            if !muted, !isFullScreen {
                Image(systemName: "speaker.wave.2.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(Theme.text)
                    .padding(12)
                    .background(Theme.background.opacity(0.7), in: Circle())
                    .padding(16)
            }
        }
        .overlay(alignment: .bottomLeading) {
            // The name shows on the focused tile only, so it doesn't sit on
            // a score or a ticker in the others.
            if error != nil || (isFocused && !isFullScreen) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(stream.displayName.title)
                        .font(.tvSmall.weight(.semibold))
                        .foregroundStyle(Theme.text)
                        .lineLimit(1)
                    if let error {
                        Text("\(error) Select to try again.")
                            .font(.tvSmall)
                            .foregroundStyle(Theme.muted)
                            .lineLimit(3)
                    } else if let nowTitle {
                        Text(nowTitle)
                            .font(.tvSmall)
                            .foregroundStyle(Theme.muted)
                            .lineLimit(1)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Theme.background.opacity(0.75), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(16)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .overlay {
            if isFocused, !isFullScreen {
                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .strokeBorder(Theme.accent, lineWidth: Theme.focusRing)
            }
        }
    }
}

private struct AddTile: View {
    @Environment(\.isFocused) private var isFocused

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "plus")
                .font(.system(size: 64, weight: .medium))
            Text("Add a channel")
                .font(.tvMeta)
        }
        .foregroundStyle(isFocused ? Theme.text : Theme.muted)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            isFocused ? Theme.controlRaised : Theme.surface,
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .overlay {
            if isFocused {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Theme.accent, lineWidth: Theme.focusRing)
            }
        }
    }
}

private struct Banner: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.tvSmall.weight(.semibold))
            .foregroundStyle(Theme.text)
            .padding(.horizontal, 28)
            .padding(.vertical, 14)
            .background(Theme.background.opacity(0.8), in: Capsule())
    }
}
