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

                SettingsCard(title: "Live buffer") {
                    ForEach(PlaybackSettings.LiveBuffer.allCases) { buffer in
                        Button {
                            app.playback.liveBuffer = buffer
                        } label: {
                            HStack {
                                Text(Self.title(buffer))
                                Spacer()
                                if app.playback.liveBuffer == buffer {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: app.playback.liveBuffer == buffer, font: .tvMeta))
                    }
                    Text("How much of a live channel is loaded ahead of what you're watching. Smaller starts a little faster. Larger rides out a weak connection. Movies and episodes buffer automatically.")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
                }

                SettingsCard(title: "Live delay") {
                    ForEach(PlaybackSettings.LiveDelay.allCases) { delay in
                        Button {
                            app.playback.liveDelay = delay
                        } label: {
                            HStack {
                                Text(Self.title(delay))
                                Spacer()
                                if app.playback.liveDelay == delay {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: app.playback.liveDelay == delay, font: .tvMeta))
                    }
                    Text(Self.detail(app.playback.liveDelay))
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
                    Text("Most of the delay comes from how your provider packages the stream, which the app can't change. Live delay only affects the Apple player; KSPlayer avoids that packaging altogether. Changes take effect the next time you start a channel.")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
                }

                SettingsCard(title: "Multiview") {
                    ForEach([false, true], id: \.self) { ignoring in
                        Button {
                            app.multiviewIgnoresLimit = ignoring
                        } label: {
                            HStack {
                                Text(ignoring ? "Try up to four channels" : "Follow the account's connections")
                                Spacer()
                                if app.multiviewIgnoresLimit == ignoring {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: app.multiviewIgnoresLimit == ignoring, font: .tvMeta))
                    }
                    Text(multiviewSummary)
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)

                    if app.multiviewTSAvailable {
                        ForEach([false, true], id: \.self) { usesTS in
                            Button {
                                app.multiviewUsesTS = usesTS
                            } label: {
                                HStack {
                                    Text(usesTS ? "Play channels as MPEG-TS (KSPlayer, trial)" : "Play channels as HLS (Apple player)")
                                    Spacer()
                                    if app.multiviewUsesTS == usesTS {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                            .buttonStyle(TVRowButtonStyle(isSelected: app.multiviewUsesTS == usesTS, font: .tvMeta))
                        }
                        Text("If channels in the multiview keep stopping each other, try MPEG-TS. It asks your provider for each channel in a different way, which some providers count differently. It may make no difference.")
                            .font(.tvSmall)
                            .foregroundStyle(Theme.muted)
                            .padding(.horizontal, 24)
                    }
                }

                SettingsCard(title: "Accounts") {
                    ForEach(app.accounts, id: \.self) { account in
                        let key = AccountBook.key(account)
                        Button {
                            app.switchTo(key)
                        } label: {
                            HStack {
                                Text(account.label)
                                    .lineLimit(1)
                                Spacer()
                                if key == app.currentAccountKey {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                        .buttonStyle(TVRowButtonStyle(isSelected: key == app.currentAccountKey, font: .tvMeta))
                    }
                    Button {
                        app.addingAccount = true
                    } label: {
                        Label("Add an account", systemImage: "plus")
                    }
                    .buttonStyle(TVRowButtonStyle(font: .tvMeta))
                    Text("Each account keeps its own favorites, hidden categories, channel order and continue watching. The badge at the foot of the rail switches between them from anywhere.")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
                }

                SettingsCard(title: "Sync") {
                    Button {
                        app.syncShowing = true
                    } label: {
                        HStack {
                            Label("Sync with your phone", systemImage: "qrcode")
                            Spacer()
                            Image(systemName: "chevron.right")
                        }
                    }
                    .buttonStyle(TVRowButtonStyle(font: .tvMeta))
                    Text("Scan a code with a phone on the same Wi-Fi to sign this TV in, or to save its hidden categories, favorites and channel order and load them into another TV. Nothing is stored anywhere but your TVs and your phone.")
                        .font(.tvSmall)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 24)
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

                // Deletes this login and its continue-watching list from the TV.
                // Favorites, hidden categories and channel order stay saved.
                Button("Remove this account", role: .destructive) {
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
    fileprivate static func title(_ buffer: PlaybackSettings.LiveBuffer) -> String {
        switch buffer {
        case .oneSecond: return "1 second"
        case .twoSeconds: return "2 seconds"
        case .standard: return "4 seconds (default)"
        case .tenSeconds: return "10 seconds"
        case .thirtySeconds: return "30 seconds"
        }
    }

    fileprivate static func title(_ delay: PlaybackSettings.LiveDelay) -> String {
        switch delay {
        case .standard: return "Standard (default)"
        case .keepUp: return "Keep up"
        case .closest: return "Closest to live"
        }
    }

    fileprivate static func detail(_ delay: PlaybackSettings.LiveDelay) -> String {
        switch delay {
        case .standard:
            return "The player decides how far behind the broadcast to play. Each time the stream pauses to load, it falls a little further behind."
        case .keepUp:
            return "After the stream pauses to load, the player skips ahead to where it would have been, so the delay doesn't grow over time."
        case .closest:
            return "Keeps up, and also plays at about half the usual distance behind the broadcast. Expect more pauses on a weak connection. The live buffer can't be larger than that distance."
        }
    }

    fileprivate var multiviewSummary: String {
        let reported: String
        switch app.reportedConnections {
        case nil: reported = "Your provider doesn't say how many connections this account has."
        case 1?: reported = "Your provider reports 1 connection for this account."
        case let count?: reported = "Your provider reports \(count) connections for this account."
        }
        return "\(reported) Every channel in the multiview is a connection. Hold Select on a channel to add it. If you try more than the provider allows, the extra channels fail to start or the provider stops one that was playing."
    }

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
