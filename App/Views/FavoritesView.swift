import HillTVCore
import SwiftUI

struct FavoritesView: View {
    let client: XtreamClient

    @Environment(AppModel.self) private var app
    @State private var nowPlaying: PlayableItem?

    var body: some View {
        Group {
            if app.favorites.channels.isEmpty {
                ContentUnavailableView(
                    "No favorites yet",
                    systemImage: "star",
                    description: Text("In Live TV, press and hold Select on a channel and choose Add to Favorites.")
                )
            } else {
                List(app.favorites.channels) { stream in
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
                    .contextMenu {
                        Button("Remove from Favorites", role: .destructive) {
                            app.favorites.toggle(stream)
                        }
                    }
                }
                .frame(maxWidth: 1400)
            }
        }
        .playerCover(item: $nowPlaying)
    }
}
