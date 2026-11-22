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

/// A small live picture with no controls, for the Live TV detail pane.
///
/// Always AVPlayer, so the URL must be HLS whatever the main player engine
/// is. One instance plays one URL: give it `.id(url)` so a new channel gets
/// a new player and the old one is torn down.
struct LivePreviewView: UIViewRepresentable {
    let url: URL
    let userAgent: String
    let muted: Bool
    /// The stream couldn't be played. The caller falls back to a still.
    var onFailure: () -> Void = {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFailure: onFailure)
    }

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        // Clear until the first frame, so the still underneath shows through.
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.playerLayer.videoGravity = .resizeAspect
        context.coordinator.start(url: url, userAgent: userAgent, muted: muted, in: view)
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        context.coordinator.setMuted(muted)
    }

    static func dismantleUIView(_ view: PlayerLayerView, coordinator: Coordinator) {
        coordinator.stop()
        view.playerLayer.player = nil
    }

    @MainActor
    final class Coordinator: NSObject {
        private let onFailure: () -> Void
        private var player: AVPlayer?
        private var statusObservation: NSKeyValueObservation?
        private weak var view: PlayerLayerView?

        init(onFailure: @escaping () -> Void) {
            self.onFailure = onFailure
        }

        func start(url: URL, userAgent: String, muted: Bool, in view: PlayerLayerView) {
            // Same undocumented header key the full player uses; see AVPlayerEngineView.
            let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": userAgent]])
            let item = AVPlayerItem(asset: asset)
            item.preferredForwardBufferDuration = 2
            // The pane is under half the screen; don't pull a 4K rendition for it.
            item.preferredMaximumResolution = CGSize(width: 1280, height: 720)

            let player = AVPlayer(playerItem: item)
            player.isMuted = muted
            // A preview left on screen shouldn't keep the Apple TV awake.
            player.preventsDisplaySleepDuringVideoPlayback = false
            view.playerLayer.player = player
            self.player = player
            self.view = view

            statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                let status = item.status
                Task { @MainActor [weak self] in
                    self?.statusChanged(status)
                }
            }
            player.play()
        }

        private func statusChanged(_ status: AVPlayerItem.Status) {
            switch status {
            case .readyToPlay:
                // Black bars instead of the still peeking around a 4:3 picture.
                view?.backgroundColor = .black
            case .failed:
                onFailure()
            default:
                break
            }
        }

        func setMuted(_ muted: Bool) {
            player?.isMuted = muted
        }

        func stop() {
            statusObservation?.invalidate()
            statusObservation = nil
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
