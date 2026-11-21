import HillTVCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var app = app

        Form {
            Section("Account") {
                if let account = app.client?.account {
                    LabeledContent("Server", value: account.serverURL.absoluteString)
                    LabeledContent("Username", value: account.username)
                }
                if let user = app.authInfo?.user {
                    LabeledContent("Status", value: user.status ?? "Unknown")
                    if let expiration = user.expiration {
                        LabeledContent("Expires", value: expiration.formatted(date: .abbreviated, time: .omitted))
                    }
                    if let max = user.maxConnections {
                        LabeledContent("Connections", value: "\(user.activeConnections ?? 0) of \(max) in use")
                    }
                    if !user.allowedOutputFormats.isEmpty {
                        LabeledContent("Formats", value: user.allowedOutputFormats.joined(separator: ", "))
                    }
                }
                if let warning = app.accountWarning {
                    Text(warning)
                        .foregroundStyle(.orange)
                }
            }

            Section {
                Picker("Player", selection: $app.engine) {
                    ForEach(PlayerEngineKind.allCases.filter(\.isAvailable)) { engine in
                        Text(engine.displayName).tag(engine)
                    }
                }
                LabeledContent("Live format", value: app.liveFormat == .hls ? "HLS (.m3u8)" : "MPEG-TS (.ts)")
            } header: {
                Text("Playback")
            } footer: {
                Text(app.engine.detail)
            }

            Section {
                Button("Sign Out", role: .destructive) {
                    app.signOut()
                }
            }

            Section("About") {
                LabeledContent("Version", value: AppModel.userAgent)
                LabeledContent("KSPlayer", value: KSPlayerSupport.isLinked ? "Included" : "Not in this build")
            }
        }
        .frame(maxWidth: 1300)
    }
}
