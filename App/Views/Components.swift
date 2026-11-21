import HillTVCore
import SwiftUI

/// Left-hand category list shared by Live TV, Movies and Series. Moving focus
/// onto a category selects it (debounced by the caller), so browsing is just
/// up/down on the remote.
struct CategoryColumn: View {
    let title: String
    let categories: [XtreamCategory]
    let selectedID: String?
    var focus: FocusState<String?>.Binding
    let onSelect: (XtreamCategory) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow(text: title)
                .padding(.leading, 36)
            ScrollView(.vertical) {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(categories) { category in
                        Button {
                            onSelect(category)
                        } label: {
                            Text(category.name)
                                .lineLimit(1)
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: category.id == selectedID, minHeight: 64, font: .tvMeta))
                        .focused(focus, equals: category.id)
                    }
                }
                .padding(12)
            }
        }
    }
}

/// Channel logo on a dark tile, with a fallback. Logos are frequently dead links.
struct ChannelLogo: View {
    let url: URL?
    var size = CGSize(width: 96, height: 64)

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().scaledToFit()
            } else {
                Image(systemName: "tv")
                    .font(.system(size: size.height * 0.4))
                    .foregroundStyle(Theme.muted)
            }
        }
        .padding(size.height * 0.12)
        .frame(width: size.width, height: size.height)
        .background(Theme.control, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

struct ChannelRow: View {
    let stream: LiveStream
    let isFavorite: Bool

    var body: some View {
        HStack(spacing: 20) {
            if let number = stream.number {
                Text("\(number)")
                    .font(.tvMeta.monospacedDigit())
                    .tvSecondary()
                    .frame(width: 64, alignment: .trailing)
            }
            ChannelLogo(url: stream.iconURL)
            Text(stream.name)
                .lineLimit(1)
            Spacer(minLength: 12)
            if stream.hasArchive {
                Image(systemName: "clock.arrow.circlepath")
                    .tvSecondary()
            }
            if isFavorite {
                Image(systemName: "heart.fill")
            }
        }
    }
}

/// Poster tile for movies and series. Focus scales the artwork and draws an accent ring.
struct PosterCard: View {
    let title: String
    var subtitle: String?
    let imageURL: URL?
    var width: CGFloat = 220
    /// Watched share, drawn as a bar along the bottom of the artwork.
    var progress: Double?

    @Environment(\.isFocused) private var isFocused

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            AsyncImage(url: imageURL) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    ZStack {
                        Theme.control
                        Image(systemName: "film")
                            .font(.system(size: 48))
                            .foregroundStyle(Theme.muted)
                    }
                }
            }
            .frame(width: width, height: width * 1.5)
            .overlay(alignment: .bottom) {
                if let progress {
                    ProgressBar(value: progress)
                        .padding(12)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
            .overlay {
                if isFocused {
                    RoundedRectangle(cornerRadius: Theme.radius + 7, style: .continuous)
                        .stroke(Theme.accent, lineWidth: Theme.focusRing)
                        .padding(-9)
                }
            }
            .scaleEffect(isFocused ? Theme.focusScale : 1)
            .shadow(color: .black.opacity(isFocused ? 0.6 : 0), radius: 24, y: 12)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.tvSmall.weight(.semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                if let subtitle {
                    Text(subtitle)
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .lineLimit(1)
                }
            }
            .frame(width: width, height: 76, alignment: .topLeading)
        }
        .animation(Theme.focusAnimation, value: isFocused)
    }
}

/// Logo card for a live channel in a rail.
struct ChannelTile: View {
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

/// Titled horizontal row of tiles, used on Home and in Search.
struct Rail<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.tvHeading)
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 40)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 32) {
                    content
                }
                .padding(.horizontal, 40)
                .padding(.vertical, 24)
            }
            // Focused posters scale past the scroll view's edge; don't cut off their rings.
            .scrollClipDisabled()
        }
    }
}

/// Poster grid used by Movies and Series.
struct PosterGrid<Item: Identifiable, Tile: View>: View {
    let items: [Item]
    @ViewBuilder let tile: (Item) -> Tile

    private let columns = [GridItem(.adaptive(minimum: 220, maximum: 240), spacing: 40, alignment: .top)]

    var body: some View {
        ScrollView(.vertical) {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 48) {
                ForEach(items) { item in
                    tile(item)
                }
            }
            .padding(.horizontal, 32)
            .padding(.vertical, 40)
        }
        // Focused posters scale past the scroll view's edge; don't cut off their rings.
        .scrollClipDisabled()
    }
}

/// "1:04:12" or "34:12".
func clockString(_ seconds: Double) -> String {
    let total = max(0, Int(seconds.isFinite ? seconds : 0))
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let secs = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, secs)
        : String(format: "%d:%02d", minutes, secs)
}

extension Date {
    var shortTime: String {
        formatted(date: .omitted, time: .shortened)
    }
}
