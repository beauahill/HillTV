import AVFoundation
import SwiftUI
import UIKit

/// How the focused channel is shown in Live TV while browsing.
enum LivePreviewMode: String, CaseIterable, Identifiable {
    case off
    case muted
    case sound

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .off: return "Off"
        case .muted: return "Picture only"
        case .sound: return "Picture and sound"
        }
    }
}

/// A live picture with no controls: the Live TV detail pane's preview, and
/// each tile of the multiview.
///
/// Always AVPlayer, so the URL must be HLS whatever the main player engine
/// is. One instance plays one URL: give it `.id(url)` so a new channel gets
/// a new player and the old one is torn down.
///
/// The defaults are the preview's. The multiview passes its own.
struct LivePreviewView: UIViewRepresentable {
    let url: URL
    let userAgent: String
    let muted: Bool
    /// The largest picture to ask for, or nil for no limit. It only matters
    /// when the stream comes in more than one quality. The preview's pane is
    /// under half the screen, so it never pulls a 4K rendition.
    var maxResolution: CGSize? = CGSize(width: 1280, height: 720)
    /// A preview left on screen shouldn't keep the Apple TV awake. Channels
    /// being watched should.
    var keepsDisplayAwake = false
    /// The stream couldn't be played. The caller falls back to a still.
    var onFailure: () -> Void = {}
    /// The same, with the player's reason, for callers that show it. Also
    /// called when a stream that was playing stops for good, which
    /// `onFailure` is not: the multiview needs to say when the provider cuts
    /// a channel off, and the preview is left as it was.
    var onError: (String) -> Void = { _ in }

    func makeCoordinator() -> Coordinator {
        Coordinator(onFailure: onFailure, onError: onError)
    }

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        // Clear until the first frame, so the still underneath shows through.
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.playerLayer.videoGravity = .resizeAspect
        context.coordinator.start(
            url: url,
            userAgent: userAgent,
            muted: muted,
            maxResolution: maxResolution,
            keepsDisplayAwake: keepsDisplayAwake,
            in: view
        )
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        context.coordinator.setMuted(muted)
        context.coordinator.setMaxResolution(maxResolution)
    }

    static func dismantleUIView(_ view: PlayerLayerView, coordinator: Coordinator) {
        coordinator.stop()
        view.playerLayer.player = nil
    }

    @MainActor
    final class Coordinator: NSObject {
        private let onFailure: () -> Void
        private let onError: (String) -> Void
        private var player: AVPlayer?
        private var statusObservation: NSKeyValueObservation?
        private weak var view: PlayerLayerView?
        private var maxResolution: CGSize?
        private var itemObservers: [NSObjectProtocol] = []
        private var stallCheck: Task<Void, Never>?
        private var reportedStop = false

        init(onFailure: @escaping () -> Void, onError: @escaping (String) -> Void) {
            self.onFailure = onFailure
            self.onError = onError
        }

        func start(
            url: URL,
            userAgent: String,
            muted: Bool,
            maxResolution: CGSize?,
            keepsDisplayAwake: Bool,
            in view: PlayerLayerView
        ) {
            // Same undocumented header key the full player uses; see AVPlayerEngineView.
            let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": userAgent]])
            let item = AVPlayerItem(asset: asset)
            item.preferredForwardBufferDuration = 2
            // Zero means no limit.
            item.preferredMaximumResolution = maxResolution ?? .zero
            self.maxResolution = maxResolution

            let player = AVPlayer(playerItem: item)
            player.isMuted = muted
            player.preventsDisplaySleepDuringVideoPlayback = keepsDisplayAwake
            view.playerLayer.player = player
            self.player = player
            self.view = view

            statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                let status = item.status
                let reason = item.error?.localizedDescription
                Task { @MainActor [weak self] in
                    self?.statusChanged(status, reason: reason)
                }
            }
            watchForStops(item)
            player.play()
        }

        /// A stream the provider closes after it started doesn't fail the
        /// way one that never opens does. It either ends with an error or
        /// just stops loading and sits on its last frame.
        private func watchForStops(_ item: AVPlayerItem) {
            let center = NotificationCenter.default
            itemObservers.append(center.addObserver(
                forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main
            ) { [weak self] note in
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                let reason = error?.localizedDescription
                Task { @MainActor [weak self] in
                    self?.stopped(reason ?? "The stream stopped.")
                }
            })
            itemObservers.append(center.addObserver(
                forName: AVPlayerItem.playbackStalledNotification, object: item, queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.stalled()
                }
            })
        }

        /// A stall is often a few seconds of buffering. Only one that hasn't
        /// recovered after a while counts as stopped.
        private func stalled() {
            stallCheck?.cancel()
            stallCheck = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(15))
                guard !Task.isCancelled, let self, let player = self.player else { return }
                if player.timeControlStatus != .playing {
                    self.stopped("The stream stopped loading. The provider may have closed it.")
                }
            }
        }

        private func stopped(_ reason: String) {
            guard !reportedStop else { return }
            reportedStop = true
            onError(reason)
        }

        private func statusChanged(_ status: AVPlayerItem.Status, reason: String?) {
            switch status {
            case .readyToPlay:
                // Black bars instead of the still peeking around a 4:3 picture.
                view?.backgroundColor = .black
            case .failed:
                onFailure()
                stopped(reason ?? "This channel couldn't be played.")
            default:
                break
            }
        }

        func setMuted(_ muted: Bool) {
            player?.isMuted = muted
        }

        /// The multiview changes this as channels are added and as a tile
        /// goes full screen.
        func setMaxResolution(_ size: CGSize?) {
            guard size != maxResolution else { return }
            maxResolution = size
            player?.currentItem?.preferredMaximumResolution = size ?? .zero
        }

        func stop() {
            statusObservation?.invalidate()
            statusObservation = nil
            itemObservers.forEach(NotificationCenter.default.removeObserver)
            itemObservers = []
            stallCheck?.cancel()
            stallCheck = nil
            player?.pause()
            player?.replaceCurrentItem(with: nil)
            player = nil
        }
    }
}

/// A view whose backing layer is an `AVPlayerLayer`.
final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer {
        // layerClass above guarantees the type.
        layer as! AVPlayerLayer
    }
}
