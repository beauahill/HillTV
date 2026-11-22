import HillTVCore
import SwiftUI

struct FavoritesView: View {
    let client: XtreamClient

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Favorites")
                Text("Your channels")
                    .font(.tvTitle)
                    .foregroundStyle(Theme.text)
            }
            .padding(.leading, 36)

            if app.favorites.channels.isEmpty {
                ContentUnavailableView(
                    "No favorites yet",
                    systemImage: "heart",
                    description: Text("In Live TV, press and hold Select on a channel and choose Add to Favorites.")
                )
                .foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(app.favorites.channels) { stream in
                            Button {
                                nowPlaying = PlayableItem(
                                    id: "live-\(stream.id)",
                                    title: stream.name,
                                    url: client.liveURL(streamID: stream.id, format: app.liveFormat),
                                    isLive: true
                                )
                            } label: {
                                ChannelRow(stream: stream, isFavorite: false)
                            }
                            .buttonStyle(TVRowButtonStyle(minHeight: Theme.listRowHeight))
                            .contextMenu {
                                Button("Remove from Favorites", role: .destructive) {
                                    app.favorites.toggle(stream)
                                }
                            }
                        }
                    }
                    .padding(12)
                }
                .frame(maxWidth: 1200, alignment: .leading)
            }
        }
        .playerCover(item: $nowPlaying)
    }
}
