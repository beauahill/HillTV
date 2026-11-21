import AVKit
import SwiftUI

/// AVPlayerViewController wrapped for SwiftUI. Gives the stock tvOS transport
/// bar, info panel (title/subtitle from externalMetadata), and audio/subtitle
/// track pickers.
struct AVPlayerEngineView: UIViewControllerRepresentable {
    let item: PlayableItem
    let userAgent: String
    @Binding var errorMessage: String?

    func makeCoordinator() -> Coordinator {
        Coordinator(errorMessage: $errorMessage)
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
        context.coordinator.observe(playerItem)

        let controller = AVPlayerViewController()
        controller.player = player
        player.play()
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
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
        private var statusObservation: NSKeyValueObservation?

        init(errorMessage: Binding<String?>) {
            self.errorMessage = errorMessage
        }

        func observe(_ item: AVPlayerItem) {
            statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
                guard item.status == .failed else { return }
                let message = item.error?.localizedDescription ?? "Unknown playback error."
                DispatchQueue.main.async {
                    self?.errorMessage.wrappedValue = message
                }
            }
        }

        func stop() {
            statusObservation?.invalidate()
            statusObservation = nil
        }
    }
}
