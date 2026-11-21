import Foundation
import HillTVCore
import Observation

@MainActor
@Observable
final class AppModel {
    enum Phase {
        case launching
        case signedOut
        case signedIn
    }

    private(set) var phase: Phase = .launching
    private(set) var client: XtreamClient?
    private(set) var authInfo: XtreamAuthInfo?
    private(set) var accountWarning: String?

    var engine: PlayerEngineKind {
        didSet { UserDefaults.standard.set(engine.rawValue, forKey: Self.engineKey) }
    }

    /// What the user picked in Settings, or nil if they haven't.
    var previewChoice: LivePreviewMode? {
        didSet { UserDefaults.standard.set(previewChoice?.rawValue, forKey: Self.previewKey) }
    }

    let favorites = FavoritesStore()
    let watchHistory = WatchHistoryStore()

    static let userAgent: String = {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return "HillTV/\(version)"
    }()

    private static let engineKey = "playerEngine"
    private static let previewKey = "livePreview"

    init() {
        let stored = UserDefaults.standard.string(forKey: Self.engineKey).flatMap(PlayerEngineKind.init(rawValue:))
        if let stored, stored.isAvailable {
            engine = stored
        } else {
            engine = .defaultEngine
        }
        previewChoice = UserDefaults.standard.string(forKey: Self.previewKey).flatMap(LivePreviewMode.init(rawValue:))
    }

    /// Whether Live TV plays the focused channel in its detail pane.
    ///
    /// A preview holds a connection, and panels can take a few seconds to
    /// release one after it stops. So it only defaults on when the account
    /// clearly has a connection to spare.
    var previewMode: LivePreviewMode {
        if let previewChoice { return previewChoice }
        return hasSpareConnection ? .muted : .off
    }

    /// False when the panel allows one connection or doesn't say.
    var hasSpareConnection: Bool {
        (authInfo?.user.maxConnections ?? 1) >= 2
    }

    /// The preview is always AVPlayer, which needs HLS.
    var previewSupported: Bool {
        let formats = authInfo?.user.allowedOutputFormats ?? []
        return formats.isEmpty || formats.contains("m3u8")
    }

    /// Container to request for live channels with the current engine.
    var liveFormat: LiveFormat {
        let allowed = authInfo?.user.allowedOutputFormats ?? []
        switch engine {
        case .avPlayer:
            return .hls
        case .ksPlayer:
            // Raw TS starts faster; fall back to HLS if the panel doesn't offer TS.
            return (allowed.isEmpty || allowed.contains("ts")) ? .mpegts : .hls
        }
    }

    // MARK: Session

    func restore() async {
        guard phase == .launching else { return }
        guard let account = AccountStore.load() else {
            phase = .signedOut
            return
        }
        let client = XtreamClient(account: account, userAgent: Self.userAgent)
        self.client = client
        watchHistory.activate(account: account)
        phase = .signedIn

        // Refresh account details without holding up the UI.
        do {
            authInfo = try await client.authenticate()
            accountWarning = nil
        } catch {
            accountWarning = error.localizedDescription
        }
    }

    func signIn(server: String, username: String, password: String) async throws {
        guard let serverURL = XtreamAccount.normalizeServer(server) else {
            throw XtreamError.invalidServerURL
        }
        let account = XtreamAccount(
            serverURL: serverURL,
            username: username.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password
        )
        let client = XtreamClient(account: account, userAgent: Self.userAgent)
        let info = try await client.authenticate()

        AccountStore.save(account)
        watchHistory.activate(account: account)
        self.client = client
        authInfo = info
        accountWarning = nil
        phase = .signedIn
    }

    func signOut() {
        AccountStore.delete()
        watchHistory.clear()
        client = nil
        authInfo = nil
        accountWarning = nil
        phase = .signedOut
    }
}
