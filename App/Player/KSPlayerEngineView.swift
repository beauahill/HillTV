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

    var body: some View {
        // KSVideoPlayerView holds its model in a StateObject, so the options
        // built here are only used on first appearance. The coordinator label
        // picks one init explicitly; KSPlayer has two that overlap.
        KSVideoPlayerView(coordinator: nil, url: item.url, options: makeOptions(), title: item.title, subtitleDataSource: nil)
    }

    private func makeOptions() -> KSOptions {
        let options = KSOptions()
        options.isLive = item.isLive
        options.userAgent = userAgent
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

    var body: some View {
        Text("KSPlayer isn't included in this build.")
    }
}

#endif
