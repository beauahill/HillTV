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
    /// Called with (position, duration) in seconds while a movie or episode plays, and once on close.
    var onProgress: (@MainActor (Double, Double?) -> Void)?

    /// Ours rather than one KSPlayer makes, so playback errors can be heard.
    @State private var coordinator = KSVideoPlayer.Coordinator()
    /// A reference, so KSPlayer's many position reports don't redraw the player.
    @State private var progress = ProgressBox()

    var body: some View {
        // KSVideoPlayerView holds its model in a StateObject, so the options
        // built here are only used on first appearance. The coordinator label
        // picks one init explicitly; KSPlayer has two that overlap.
        //
        // Menu is handled inside KSVideoPlayerView: it hides the controls if
        // they are showing, and otherwise dismisses the cover.
        KSVideoPlayerView(coordinator: coordinator, url: item.url, options: makeOptions(), title: item.title, subtitleDataSource: nil)
            // KSPlayer's screens are built for the system's own colors. The
            // app's blue tint and text color are inherited by everything it
            // presents, and its settings panel (Down on the remote) sits
            // outside the white tint KSPlayer gives its control bar. So give
            // the whole player that white tint, and clear the app's text
            // color so focused rows can switch to dark text again.
            .tint(.white)
            .foregroundColor(nil)
            .onAppear {
                // Without this a dead channel is a black screen with no message.
                coordinator.onFinish = { _, error in
                    if let error {
                        errorMessage = error.localizedDescription
                    }
                }
                trackProgress()
            }
            .onDisappear {
                // KSPlayer drops its callbacks as it closes, so the last
                // position comes from our own copy.
                if let onProgress, let last = progress.tracker?.latest {
                    MainActor.assumeIsolated { onProgress(last.position, last.duration) }
                }
            }
    }

    /// Movies and episodes only. KSPlayer reports its position many times a
    /// second; `ProgressTracker` saves one in every ten seconds and skips
    /// the reports from before a resume point is reached.
    private func trackProgress() {
        guard let onProgress, !item.isLive, progress.tracker == nil else { return }
        let box = progress
        box.tracker = ProgressTracker(resumeFrom: item.startAt)
        coordinator.onPlay = { position, duration in
            guard let report = box.tracker?.report(position: position, duration: duration) else { return }
            // KSPlayer calls this from its main-actor delegate.
            MainActor.assumeIsolated { onProgress(report.position, report.duration) }
        }
    }

    private func makeOptions() -> KSOptions {
        let options = KSOptions()
        options.isLive = item.isLive
        options.userAgent = userAgent
        if item.isLive {
            options.preferredForwardBufferDuration = settings.liveBuffer.seconds
        } else if let start = item.startAt, start > 0 {
            options.startPlayTime = start
        }
        return options
    }
}

private final class ProgressBox {
    var tracker: ProgressTracker?
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
    var onProgress: (@MainActor (Double, Double?) -> Void)?

    var body: some View {
        Text("KSPlayer isn't included in this build.")
    }
}

#endif
