import HillTVCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 40) {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Settings")
                    Text("Preferences")
                        .font(.tvTitle)
                        .foregroundStyle(Theme.text)
                }

                SettingsCard(title: "Account") {
                    if let account = app.client?.account {
                        InfoRow(label: "Server", value: account.serverURL.absoluteString)
                        InfoRow(label: "Username", value: account.username)
                    }
                    if let user = app.authInfo?.user {
                        InfoRow(label: "Status", value: user.status ?? "Unknown")
                        if let expiration = user.expiration {
                            InfoRow(label: "Expires", value: expiration.formatted(date: .abbreviated, time: .omitted))
                        }
                        if let max = user.maxConnections {
                            InfoRow(label: "Connections", value: "\(user.activeConnections ?? 0) of \(max) in use")
                        }
                        if !user.allowedOutputFormats.isEmpty {
                            InfoRow(label: "Formats", value: user.allowedOutputFormats.joined(separator: ", "))
                        }
                    }
                    if let warning = app.accountWarning {
                        Text(warning)
                            .font(.tvSmall)
                            .foregroundStyle(Color(hex: 0xFBBF24))
                    }
                }

                SettingsCard(title: "Player") {
                    ForEach(PlayerEngineKind.allCases.filter(\.isAvailable)) { engine in
                        Button {
                            app.engine = engine
                        } label: {
                            HStack {
                                Text(engine.displayName)
                                Spacer()
                                if app.engine == engine {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: app.engine == engine, font: .tvMeta))
                    }
                    Text(app.engine.detail)
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
                    InfoRow(label: "Live format", value: app.liveFormat == .hls ? "HLS (.m3u8)" : "MPEG-TS (.ts)")
                }

                SettingsCard(title: "About") {
                    InfoRow(label: "Version", value: AppModel.userAgent)
                    InfoRow(label: "KSPlayer", value: KSPlayerSupport.isLinked ? "Included" : "Not in this build")
                }

                Button("Sign Out", role: .destructive) {
                    app.signOut()
                }
                .buttonStyle(TVActionButtonStyle())
            }
            .padding(.horizontal, 36)
            .padding(.vertical, 24)
            .frame(maxWidth: 1200, alignment: .leading)
        }
    }
}

private struct SettingsCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.tvHeading)
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 24)
            content
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
    }
}

private struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 24) {
            Text(label)
                .foregroundStyle(Theme.muted)
            Spacer(minLength: 24)
            Text(value)
                .foregroundStyle(Theme.text)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .font(.tvMeta)
        .padding(.horizontal, 24)
        .padding(.vertical, 6)
    }
}
