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
    /// A reference, so KSPlayer's position reports don't redraw the player.
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

    /// Movies and episodes only. KSPlayer reports its position as it plays,
    /// more often than is worth saving; `ProgressTracker` saves once per ten
    /// seconds of film and skips the reports from before a resume point is
    /// reached.
    private func trackProgress() {
        guard let onProgress, !item.isLive else { return }
        let box = progress
        // One tracker for the life of the player, but the callback is set on
        // every appearance, like onFinish: KSPlayer clears both when its
        // view is taken down.
        if box.tracker == nil {
            box.tracker = ProgressTracker(resumeFrom: item.startAt)
        }
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
        // Subtitles start off, as they do in the Apple player. KSPlayer
        // otherwise turns on a file's own subtitles, and its "Off" was
        // reported not to work on the TV.
        options.autoSelectEmbedSubtitle = false
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

/// One multiview tile played by KSPlayer: the picture alone, no controls.
///
/// A trial. The multiview's tiles are normally the Apple player with HLS,
/// and on an account with one connection the provider was seen closing one
/// HLS stream when a second started. This plays the raw MPEG-TS stream
/// instead, one long-lived request per channel, to find out whether the
/// provider treats that differently. Nothing says it will.
struct KSPlayerTileView: View {
    let url: URL
    let muted: Bool
    /// The stream failed or ended. Live streams don't end by themselves.
    var onError: (String) -> Void = { _ in }

    @State private var coordinator = KSVideoPlayer.Coordinator()
    /// Made once: KSPlayer only reads the options when the player is created.
    @State private var options: KSOptions

    init(url: URL, userAgent: String, muted: Bool, onError: @escaping (String) -> Void = { _ in }) {
        self.url = url
        self.muted = muted
        self.onError = onError
        let options = KSOptions()
        options.isLive = true
        options.userAgent = userAgent
        options.autoSelectEmbedSubtitle = false
        // Several players at once: none of them should claim the remote's
        // play and pause for itself.
        options.registerRemoteControll = false
        _options = State(initialValue: options)
    }

    var body: some View {
        KSVideoPlayer(coordinator: coordinator, url: url, options: options)
            .onStateChanged { _, _ in
                // The player may not exist when mute is first set, and the
                // coordinator only passes mute on when it is assigned. So
                // assign it again as the player comes up, even though the
                // value is the same.
                coordinator.isMuted = muted
            }
            .onFinish { _, error in
                onError(error?.localizedDescription ?? "The stream ended. The provider may have closed it.")
            }
            // The tile's button holds focus, never the picture.
            .focusable(false)
            .allowsHitTesting(false)
            .onAppear { setMuted(muted) }
            .onChange(of: muted) { _, value in
                setMuted(value)
            }
    }

    /// Only on a change: the coordinator announces every assignment. Not
    /// for the state handler above, which must assign regardless.
    private func setMuted(_ value: Bool) {
        if coordinator.isMuted != value {
            coordinator.isMuted = value
        }
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
    var onProgress: (@MainActor (Double, Double?) -> Void)?

    var body: some View {
        Text("KSPlayer isn't included in this build.")
    }
}

struct KSPlayerTileView: View {
    let url: URL
    let muted: Bool
    var onError: (String) -> Void = { _ in }

    init(url: URL, userAgent: String, muted: Bool, onError: @escaping (String) -> Void = { _ in }) {
        self.url = url
        self.muted = muted
        self.onError = onError
    }

    var body: some View {
        Color.clear
    }
}

#endif
