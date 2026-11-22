import HillTVCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var showingCategories = false

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

                SettingsCard(title: "Categories") {
                    Button {
                        showingCategories = true
                    } label: {
                        HStack {
                            Text("Choose what to show")
                            Spacer()
                            Image(systemName: "chevron.right")
                        }
                    }
                    .buttonStyle(TVRowButtonStyle(font: .tvMeta))
                    Text(hiddenSummary)
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
                }
                .fullScreenCover(isPresented: $showingCategories) {
                    if let client = app.client {
                        CategoryManagerView(client: client, store: app.categoryVisibility)
                    }
                }

                SettingsCard(title: "Live TV preview") {
                    ForEach(LivePreviewMode.allCases) { mode in
                        Button {
                            app.previewChoice = mode
                        } label: {
                            HStack {
                                Text(mode.displayName)
                                Spacer()
                                if app.previewMode == mode {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: app.previewMode == mode, font: .tvMeta))
                    }
                    Text(previewNote)
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
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

extension SettingsView {
    fileprivate var hiddenSummary: String {
        let store = app.categoryVisibility
        let counts = CatalogKind.allCases.map { store.hiddenCount(in: $0) }
        if counts.allSatisfy({ $0 == 0 }) {
            return "Nothing is hidden. Hide the categories you never watch to shorten every list."
        }
        return "Hidden: \(counts[0]) live, \(counts[1]) movie and \(counts[2]) series categories. Saved on this Apple TV only."
    }

    fileprivate var previewNote: String {
        if !app.previewSupported {
            return "This provider doesn't offer HLS, so the preview can't play. Live TV shows a still instead."
        }
        var note = "Plays the focused channel in Live TV after a short pause. It uses one connection while it plays."
        if app.previewChoice == nil, !app.hasSpareConnection {
            note += " Off for now because this account allows one connection."
        }
        return note
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
