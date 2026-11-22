import AVKit
import SwiftUI

/// AVPlayerViewController wrapped for SwiftUI. Gives the stock tvOS transport
/// bar, info panel (title/subtitle from externalMetadata), and audio/subtitle
/// track pickers.
struct AVPlayerEngineView: UIViewControllerRepresentable {
    let item: PlayableItem
    let userAgent: String
    @Binding var errorMessage: String?
    var onProgress: (@MainActor (Double, Double?) -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(errorMessage: $errorMessage, onProgress: onProgress)
    }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        // "AVURLAssetHTTPHeaderFieldsKey" is undocumented but long-standing and
        // widely used. Some panels reject requests without a familiar User-Agent.
        let asset = AVURLAsset(url: item.url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": userAgent]])
        let playerItem = AVPlayerItem(asset: asset)
        playerItem.externalMetadata = Self.metadata(for: item)
        if item.isLive {
            // Small buffer keeps live channels closer to real time.
            playerItem.preferredForwardBufferDuration = 4
        }

        let player = AVPlayer(playerItem: playerItem)
        context.coordinator.observe(playerItem, player: player, startAt: item.isLive ? nil : item.startAt)

        let controller = AVPlayerViewController()
        controller.player = player
        player.play()
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        // Save the final position, then remove the time observer from the same
        // player that registered it, before letting the player go.
        coordinator.reportProgress()
        coordinator.stop()
        controller.player?.pause()
        controller.player = nil
    }

    private static func metadata(for item: PlayableItem) -> [AVMetadataItem] {
        func make(_ identifier: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let metadata = AVMutableMetadataItem()
            metadata.identifier = identifier
            metadata.value = value as NSString
            metadata.extendedLanguageTag = "und"
            return metadata
        }
        var items = [make(.commonIdentifierTitle, item.title)]
        if let subtitle = item.subtitle {
            items.append(make(.iTunesMetadataTrackSubTitle, subtitle))
        }
        return items
    }

    final class Coordinator: NSObject {
        private let errorMessage: Binding<String?>
        private let onProgress: (@MainActor (Double, Double?) -> Void)?
        private var statusObservation: NSKeyValueObservation?
        private var timeObserver: Any?
        private weak var player: AVPlayer?
        private var pendingStart: Double?

        init(errorMessage: Binding<String?>, onProgress: (@MainActor (Double, Double?) -> Void)?) {
            self.errorMessage = errorMessage
            self.onProgress = onProgress
        }

        func observe(_ item: AVPlayerItem, player: AVPlayer, startAt: Double?) {
            self.player = player
            if let startAt, startAt > 0 { pendingStart = startAt }

            statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                DispatchQueue.main.async {
                    self?.statusChanged(item)
                }
            }

            if onProgress != nil {
                // queue: .main delivers on the main thread; the callback itself
                // isn't main-actor isolated, so reportProgress hops explicitly.
                timeObserver = player.addPeriodicTimeObserver(
                    forInterval: CMTime(seconds: 10, preferredTimescale: 1),
                    queue: .main
                ) { [weak self] _ in
                    self?.reportProgress()
                }
            }
        }

        private func statusChanged(_ item: AVPlayerItem) {
            switch item.status {
            case .readyToPlay:
                // Seeking before the item is ready can be ignored, so resume here.
                if let start = pendingStart {
                    pendingStart = nil
                    player?.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                }
            case .failed:
                errorMessage.wrappedValue = item.error?.localizedDescription ?? "Unknown playback error."
            default:
                break
            }
        }

        func reportProgress() {
            guard let onProgress, let player, let item = player.currentItem else { return }
            // Still waiting to seek to the resume point: don't overwrite it with 0.
            guard pendingStart == nil else { return }
            let position = player.currentTime().seconds
            let duration = item.duration.seconds
            MainActor.assumeIsolated {
                onProgress(position, duration.isFinite ? duration : nil)
            }
        }

        func stop() {
            statusObservation?.invalidate()
            statusObservation = nil
            if let timeObserver, let player {
                player.removeTimeObserver(timeObserver)
            }
            timeObserver = nil
        }
    }
}
