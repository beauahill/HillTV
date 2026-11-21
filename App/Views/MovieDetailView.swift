import HillTVCore
import SwiftUI

/// Movie page: backdrop, poster, title, metadata pills, plot and Watch.
/// Opens immediately with what the list already knows, then fills in from
/// `get_vod_info`.
struct MovieDetailView: View {
    let client: XtreamClient
    let movie: VODStream

    @State private var info: VODInfo?
    @State private var nowPlaying: PlayableItem?
    @FocusState private var watchFocused: Bool

    private var title: String { info?.name ?? movie.name }
    private var poster: URL? { info?.posterURL ?? movie.posterURL }

    private var pills: [String] {
        var result: [String] = []
        if let year = info?.year { result.append(year) }
        if let genre = info?.genre { result.append(genre) }
        if let seconds = info?.durationSeconds, seconds > 0 { result.append("\(seconds / 60) min") }
        if let rating = info?.rating ?? movie.rating, rating > 0 { result.append(String(format: "★ %.1f", rating)) }
        result.append(movie.containerExtension.uppercased())
        return result
    }

    var body: some View {
        HStack(alignment: .top, spacing: 56) {
            AsyncImage(url: poster) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    Theme.control
                }
            }
            .frame(width: 300, height: 450)
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
            .shadow(color: .black.opacity(0.5), radius: 30, y: 16)

            VStack(alignment: .leading, spacing: 24) {
                Eyebrow(text: "Movie")
                Text(title)
                    .font(.tvTitle)
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                FlowLayout(spacing: 10) {
                    ForEach(pills, id: \.self) { Pill(text: $0) }
                }
                if let plot = info?.plot {
                    Text(plot)
                        .font(.tvMeta)
                        .foregroundStyle(Theme.text.opacity(0.9))
                        .lineSpacing(6)
                        .lineLimit(5)
                        .frame(maxWidth: 1000, alignment: .leading)
                }

                HStack(spacing: 24) {
                    Button {
                        play()
                    } label: {
                        Label("Watch now", systemImage: "play.fill")
                    }
                    .buttonStyle(TVActionButtonStyle(prominent: true))
                    .focused($watchFocused)
                }
                .padding(.vertical, 12)

                VStack(alignment: .leading, spacing: 10) {
                    if let director = info?.director {
                        credit("Director", director)
                    }
                    if let cast = info?.cast {
                        credit("Cast", cast)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 40)
        .padding(.top, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background { Backdrop(url: info?.backdropURL ?? poster) }
        .defaultFocus($watchFocused, true)
        .task { await load() }
        .playerCover(item: $nowPlaying)
    }

    private func credit(_ label: String, _ value: String) -> some View {
        (Text("\(label)  ").foregroundStyle(Theme.muted) + Text(value).foregroundStyle(Theme.text))
            .font(.tvSmall)
            .lineLimit(2)
            .frame(maxWidth: 1000, alignment: .leading)
    }

    private func load() async {
        guard info == nil else { return }
        info = try? await client.vodInfo(streamID: movie.id)
    }

    private func play() {
        nowPlaying = PlayableItem(
            id: "movie-\(movie.id)",
            title: title,
            subtitle: info?.year,
            url: client.movieURL(movie),
            isLive: false
        )
    }
}
