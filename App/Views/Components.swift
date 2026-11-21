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
        VStack(alignment: .leading, spacing: 16) {
            Text(title)
                .font(.headline)
                .foregroundStyle(.secondary)
                .padding(.leading, 20)
            List(categories) { category in
                Button {
                    onSelect(category)
                } label: {
                    HStack {
                        Text(category.name)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        if category.id == selectedID {
                            Image(systemName: "chevron.right")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .focused(focus, equals: category.id)
            }
        }
    }
}

/// Channel logo with a fallback. Logos are frequently dead links.
struct ChannelLogo: View {
    let url: URL?
    var size = CGSize(width: 96, height: 64)

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image {
                image.resizable().scaledToFit()
            } else {
                Image(systemName: "tv")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size.width, height: size.height)
    }
}

struct ChannelRow: View {
    let stream: LiveStream
    let isFavorite: Bool

    var body: some View {
        HStack(spacing: 24) {
            if let number = stream.number {
                Text("\(number)")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 70, alignment: .trailing)
            }
            ChannelLogo(url: stream.iconURL)
            Text(stream.name)
                .lineLimit(1)
            Spacer(minLength: 12)
            if stream.hasArchive {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundStyle(.secondary)
            }
            if isFavorite {
                Image(systemName: "star.fill")
                    .foregroundStyle(.yellow)
            }
        }
    }
}

/// Poster tile for movies and series.
struct PosterCard: View {
    let title: String
    let imageURL: URL?

    var body: some View {
        VStack(spacing: 0) {
            AsyncImage(url: imageURL) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    ZStack {
                        Color.gray.opacity(0.25)
                        Image(systemName: "film")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(width: 240, height: 360)
            .clipped()

            Text(title)
                .font(.caption)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .frame(width: 220, height: 70)
                .padding(.horizontal, 10)
        }
    }
}

extension Date {
    var shortTime: String {
        formatted(date: .omitted, time: .shortened)
    }
}
