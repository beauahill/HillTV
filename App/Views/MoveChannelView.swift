import HillTVCore
import SwiftUI

/// A channel the viewer is moving, and the list it is in.
struct ChannelMove: Identifiable {
    /// The list's name, shown as the heading.
    let listTitle: String
    /// The list as it is shown now.
    let channels: [LiveStream]
    let stream: LiveStream

    var id: Int { stream.id }
}

/// Move mode: the list, with the chosen channel riding on the focused row.
///
/// Up and down are ordinary focus moves between rows. The rows are slots,
/// identified by position, and the moving channel is drawn in whichever slot
/// has focus, with the others closing up around it. Select drops it there,
/// and only there: a press on any other row brings the channel to that row
/// first. Menu closes the page, like any full-screen cover, and nothing changes.
///
/// Built on focus moving row to row, which is known to work on the TV,
/// rather than on catching the remote's arrows with the list held still.
struct MoveChannelView: View {
    let move: ChannelMove
    /// Called with the position the channel was dropped at.
    let onDrop: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @FocusState private var focus: Int?
    /// The slot the moving channel is drawn in.
    @State private var target: Int
    /// False until focus has been put on the moving channel. Before that,
    /// focus is wherever the system first placed it, which must not count
    /// as a move.
    @State private var armed = false

    private let others: [LiveStream]
    private let start: Int

    init(move: ChannelMove, onDrop: @escaping (Int) -> Void) {
        self.move = move
        self.onDrop = onDrop
        let start = move.channels.firstIndex { $0.id == move.stream.id } ?? 0
        self.start = start
        others = move.channels.filter { $0.id != move.stream.id }
        _target = State(initialValue: start)
    }

    private var count: Int { others.count + 1 }

    private func stream(at slot: Int) -> LiveStream {
        if slot == target { return move.stream }
        return others[slot < target ? slot : slot - 1]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Move channel")
                Text(move.listTitle)
                    .font(.tvTitle)
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
            }
            .padding(.leading, 36)

            ScrollViewReader { proxy in
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(0..<count, id: \.self) { slot in
                            Button {
                                if slot == target {
                                    onDrop(slot)
                                    dismiss()
                                } else {
                                    // Focus never reached the moving row, so
                                    // the channel isn't drawn here yet. Bring
                                    // it here first; the next Select drops it.
                                    target = slot
                                    armed = true
                                }
                            } label: {
                                MoveRow(stream: stream(at: slot), position: slot + 1, isMoving: slot == target)
                            }
                            .buttonStyle(TVRowButtonStyle(isSelected: slot == target, minHeight: Theme.listRowHeight))
                            .focused($focus, equals: slot)
                            .id(slot)
                        }
                    }
                    .padding(12)
                }
                .task {
                    // A row that isn't on screen can't take focus, so bring
                    // the channel's row into view first.
                    proxy.scrollTo(start, anchor: .center)
                    try? await Task.sleep(for: .milliseconds(300))
                    focus = start
                    armed = true
                }
            }
            .frame(maxWidth: 1200, alignment: .leading)

            Text("Up and down move it. Select drops it. Menu cancels.")
                .font(.tvSmall)
                .foregroundStyle(Theme.muted)
                .padding(.leading, 36)
        }
        .padding(.horizontal, 60)
        .padding(.vertical, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onChange(of: focus) { _, slot in
            if armed, let slot { target = slot }
        }
    }
}

private struct MoveRow: View {
    let stream: LiveStream
    let position: Int
    let isMoving: Bool

    var body: some View {
        HStack(spacing: 20) {
            Group {
                if isMoving {
                    Image(systemName: "arrow.up.arrow.down")
                } else {
                    Text("\(position)")
                        .tvSecondary()
                }
            }
            .font(.tvMeta.monospacedDigit())
            .frame(width: 64, alignment: .trailing)
            ChannelLogo(url: stream.iconURL)
            ChannelNameLabel(name: stream.displayName, inFocusRow: true)
            Spacer(minLength: 12)
            if isMoving {
                Text("Moving")
                    .font(.tvSmall.weight(.semibold))
            }
        }
    }
}

extension View {
    /// Presents Move mode full screen whenever `move` is set.
    func moveChannelCover(_ move: Binding<ChannelMove?>, onDrop: @escaping (ChannelMove, Int) -> Void) -> some View {
        fullScreenCover(item: move) { item in
            ZStack {
                Theme.background.ignoresSafeArea()
                MoveChannelView(move: item) { index in onDrop(item, index) }
            }
            .foregroundStyle(Theme.text)
            .tint(Theme.accent)
            .preferredColorScheme(.dark)
        }
    }
}

extension ChannelMove {
    /// Starts Move mode once the hold-Select menu that asked for it has
    /// closed. A cover presented while the menu is still closing can be
    /// dropped, the same reason the game channel list waits before playing.
    @MainActor
    static func begin(_ move: ChannelMove, into binding: Binding<ChannelMove?>) {
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            binding.wrappedValue = move
        }
    }
}
