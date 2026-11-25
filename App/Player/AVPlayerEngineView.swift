import AVKit
import HillTVCore
import SwiftUI

/// AVPlayerViewController wrapped for SwiftUI. Gives the stock tvOS transport
/// bar, info panel (title/subtitle from externalMetadata), and audio/subtitle
/// track pickers.
struct AVPlayerEngineView: UIViewControllerRepresentable {
    let item: PlayableItem
    let userAgent: String
    /// Live buffer and live delay. The defaults change nothing here.
    var settings = PlaybackSettings()
    @Binding var errorMessage: String?
    var onProgress: (@MainActor (Double, Double?) -> Void)?
    /// When set, a file that never starts playing is reported here instead
    /// of through `errorMessage`, so another player can try it. A failure
    /// after playback has started is always an error.
    var onFailedToStart: (@MainActor (String) -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(
            errorMessage: $errorMessage,
            onProgress: onProgress,
            onFailedToStart: onFailedToStart,
            liveSettings: item.isLive ? settings : nil
        )
    }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        // "AVURLAssetHTTPHeaderFieldsKey" is undocumented but long-standing and
        // widely used. Some panels reject requests without a familiar User-Agent.
        let asset = AVURLAsset(url: item.url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": userAgent]])
        let playerItem = AVPlayerItem(asset: asset)
        playerItem.externalMetadata = Self.metadata(for: item)
        if item.isLive {
            // A small buffer keeps live channels closer to real time. 4 s unless changed in Settings.
            playerItem.preferredForwardBufferDuration = settings.liveBuffer.seconds
            if settings.preservesLiveOffset {
                // After a stall, skip ahead to the distance from live it had
                // before, instead of staying that much further behind.
                playerItem.automaticallyPreservesTimeOffsetFromLive = true
            }
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
        private let onFailedToStart: (@MainActor (String) -> Void)?
        private var becameReady = false
        /// Set for live channels only.
        private let liveSettings: PlaybackSettings?
        private var appliedLiveOffset = false
        private var statusObservation: NSKeyValueObservation?
        private var timeObserver: Any?
        private weak var player: AVPlayer?
        private var pendingStart: Double?

        init(
            errorMessage: Binding<String?>,
            onProgress: (@MainActor (Double, Double?) -> Void)?,
            onFailedToStart: (@MainActor (String) -> Void)?,
            liveSettings: PlaybackSettings?
        ) {
            self.errorMessage = errorMessage
            self.onProgress = onProgress
            self.onFailedToStart = onFailedToStart
            self.liveSettings = liveSettings
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
                becameReady = true
                // Seeking before the item is ready can be ignored, so resume here.
                if let start = pendingStart {
                    pendingStart = nil
                    player?.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                }
                moveCloserToLive(item)
            case .failed:
                let message = item.error?.localizedDescription ?? "Unknown playback error."
                if !becameReady, let onFailedToStart {
                    // statusChanged always runs on the main queue.
                    MainActor.assumeIsolated { onFailedToStart(message) }
                } else {
                    errorMessage.wrappedValue = message
                }
            default:
                break
            }
        }

        /// "Closest to live" only, once per channel. The stream's recommended
        /// distance is known only after it has loaded, so this can't be set
        /// up front. Every other setting returns no target and leaves the
        /// player exactly as it was.
        private func moveCloserToLive(_ item: AVPlayerItem) {
            guard let liveSettings, !appliedLiveOffset else { return }
            appliedLiveOffset = true
            let recommended = item.recommendedTimeOffsetFromLive
            guard let target = liveSettings.liveOffsetTarget(recommended: recommended.isNumeric ? recommended.seconds : nil) else { return }
            item.configuredTimeOffsetFromLive = CMTime(seconds: target, preferredTimescale: 600)
            // A live stream can't hold more ahead than it plays behind the live edge.
            item.preferredForwardBufferDuration = liveSettings.liveBufferSeconds(offsetTarget: target)
            // The new distance takes effect on a seek to the live edge.
            player?.seek(to: CMTime.positiveInfinity)
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
