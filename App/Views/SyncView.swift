import CoreImage
import CoreImage.CIFilterBuiltins
import HillTVCore
import SwiftUI
import UIKit

/// The Sync screen: a QR code for a phone on the same Wi-Fi, the address
/// and code to type instead, and what the phone has done so far. The web
/// server runs only while this is on screen.
///
/// Presented from `RootView`, above the login screen and the shell, so it
/// stays up when the phone signs the TV in and everything below is rebuilt.
struct SyncView: View {
    @Environment(AppModel.self) private var app

    private var sync: SyncServer { app.sync }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            content
                .padding(80)
        }
        .foregroundStyle(Theme.text)
        .onAppear {
            sync.start()
            // The TV stays awake while the viewer is busy on the phone.
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            sync.stop()
            UIApplication.shared.isIdleTimerDisabled = false
        }
        // The page stops working when the app leaves the screen.
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
            app.syncShowing = false
        }
    }

    @ViewBuilder
    private var content: some View {
        switch sync.status {
        case .stopped, .starting:
            ProgressView()
                .tint(Theme.accent)
        case .failed(let reason):
            failure(reason)
        case .ready(let url, let address, let code):
            ready(url: url, address: address, code: code)
        }
    }

    private func ready(url: URL, address: String, code: String) -> some View {
        HStack(alignment: .center, spacing: 80) {
            QRCodeImage(text: url.absoluteString)
                .frame(width: 440, height: 440)
                .padding(24)
                .background(Color.white, in: RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))

            VStack(alignment: .leading, spacing: 20) {
                Eyebrow(text: "Sync")
                Text("Scan with your phone")
                    .font(.tvTitle)
                Text("Point your phone's camera at the code. The phone must be on the same Wi-Fi as this Apple TV. From there you can sign this TV in, or save and load its hidden categories, favorites and channel order.")
                    .font(.tvMeta)
                    .foregroundStyle(Theme.muted)
                    .frame(maxWidth: 900, alignment: .leading)

                VStack(alignment: .leading, spacing: 6) {
                    Text("Or open this address and type the code")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                    Text(address)
                        .font(.tvBody.monospaced())
                    Text(String(code.prefix(3)) + " " + String(code.suffix(3)))
                        .font(.system(size: 56, weight: .semibold).monospacedDigit())
                        .tracking(6)
                }
                .padding(.top, 8)

                if sync.isLocked {
                    Text("Too many wrong codes. Close Sync and open it again to get a new code.")
                        .font(.tvMeta)
                        .foregroundStyle(Theme.live)
                }

                if !sync.activity.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(sync.activity.enumerated()), id: \.offset) { _, line in
                            Label(line, systemImage: "checkmark.circle")
                                .font(.tvSmall)
                        }
                    }
                    .padding(.top, 8)
                }

                Button("Done") {
                    app.syncShowing = false
                }
                .buttonStyle(TVActionButtonStyle(prominent: true))
                .padding(.top, 12)
            }
        }
    }

    private func failure(_ text: String) -> some View {
        VStack(spacing: 28) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 84))
                .foregroundStyle(Theme.muted)
            Text("Sync isn't available")
                .font(.tvTitle)
            Text(text)
                .font(.tvMeta)
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 1000)
            HStack(spacing: 24) {
                Button("Try again") { sync.start() }
                    .buttonStyle(TVActionButtonStyle(prominent: true))
                Button("Close") { app.syncShowing = false }
                    .buttonStyle(TVActionButtonStyle())
            }
        }
    }
}

/// A QR code drawn with Core Image, kept sharp when scaled up.
private struct QRCodeImage: View {
    let text: String

    var body: some View {
        if let image = Self.make(text) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            Image(systemName: "qrcode")
                .resizable()
                .scaledToFit()
                .foregroundStyle(Theme.background)
        }
    }

    private static func make(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        return CIContext().createCGImage(scaled, from: scaled.extent)
    }
}
