import HillTVCore
import SwiftUI

// This file compiles two ways. With project-ksplayer.yml the KSPlayer package
// is linked and the real engine is built. With plain project.yml it isn't, and
// a stub keeps the rest of the app compiling with no #if anywhere else.

#if canImport(KSPlayerUI)
import KSPlayer
import KSPlayerUI

enum KSPlayerSupport {
    static let isLinked = true

    @MainActor
    static func configure() {
        // FFmpeg first: most IPTV is MPEG-TS, which AVPlayer can't open.
        // AVPlayer second as a fallback for anything FFmpeg chokes on.
        KSOptions.firstPlayerType = KSMEPlayer.self
        KSOptions.secondPlayerType = KSAVPlayer.self
    }
}

struct KSPlayerEngineView: View {
    let item: PlayableItem
    let userAgent: String
    let settings: PlaybackSettings
    @Binding var errorMessage: String?

    /// Ours rather than one KSPlayer makes, so playback errors can be heard.
    @State private var coordinator = KSVideoPlayer.Coordinator()

    var body: some View {
        // KSVideoPlayerView holds its model in a StateObject, so the options
        // built here are only used on first appearance. The coordinator label
        // picks one init explicitly; KSPlayer has two that overlap.
        //
        // Menu is handled inside KSVideoPlayerView: it hides the controls if
        // they are showing, and otherwise dismisses the cover.
        KSVideoPlayerView(coordinator: coordinator, url: item.url, options: makeOptions(), title: item.title, subtitleDataSource: nil)
            .onAppear {
                // Without this a dead channel is a black screen with no message.
                coordinator.onFinish = { _, error in
                    if let error {
                        errorMessage = error.localizedDescription
                    }
                }
            }
    }

    private func makeOptions() -> KSOptions {
        let options = KSOptions()
        options.isLive = item.isLive
        options.userAgent = userAgent
        if item.isLive {
            options.preferredForwardBufferDuration = settings.liveBuffer.seconds
        }
        return options
    }
}

#else

enum KSPlayerSupport {
    static let isLinked = false

    @MainActor
    static func configure() {}
}

struct KSPlayerEngineView: View {
    let item: PlayableItem
    let userAgent: String
    let settings: PlaybackSettings
    @Binding var errorMessage: String?

    var body: some View {
        Text("KSPlayer isn't included in this build.")
    }
}

#endif
