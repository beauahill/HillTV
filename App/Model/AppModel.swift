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
    /// The login screen is showing to add another account, over a TV that
    /// is signed in. Menu or Cancel there goes back.
    var addingAccount = false

    private var book = AccountBook()
    /// Account details by account key, so a switch shows the right
    /// connection count and formats straight away.
    private var authCache: [String: XtreamAuthInfo] = [:]

    var engine: PlayerEngineKind {
        didSet { UserDefaults.standard.set(engine.rawValue, forKey: Self.engineKey) }
    }

    /// Live buffer and live delay. The defaults leave the player as it always was.
    var playback: PlaybackSettings {
        didSet {
            if let data = try? JSONEncoder().encode(playback) {
                UserDefaults.standard.set(data, forKey: Self.playbackKey)
            }
        }
    }

    /// What the user picked in Settings, or nil if they haven't.
    var previewChoice: LivePreviewMode? {
        didSet { UserDefaults.standard.set(previewChoice?.rawValue, forKey: Self.previewKey) }
    }

    /// The viewer chose to let the multiview try more channels than the
    /// account reports connections for. Off unless they turn it on.
    var multiviewIgnoresLimit: Bool {
        didSet { UserDefaults.standard.set(multiviewIgnoresLimit, forKey: Self.multiviewLimitKey) }
    }

    /// A trial: multiview tiles play raw MPEG-TS through KSPlayer instead of
    /// HLS through the Apple player. Off unless the viewer turns it on.
    var multiviewUsesTS: Bool {
        didSet { UserDefaults.standard.set(multiviewUsesTS, forKey: Self.multiviewTSKey) }
    }

    let favorites = FavoritesStore()
    let watchHistory = WatchHistoryStore()
    let categoryVisibility = CategoryVisibilityStore()
    let channelOrder = ChannelOrderStore()
    let multiview = MultiviewStore()
    /// The phone page's web server, for Settings > Sync and the login screen.
    let sync = SyncServer()
    /// `RootView` shows the Sync screen while this is true.
    var syncShowing = false

    static let userAgent: String = {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        return "HillTV/\(version)"
    }()

    private static let engineKey = "playerEngine"
    private static let previewKey = "livePreview"
    private static let multiviewLimitKey = "multiviewIgnoresLimit"
    private static let multiviewTSKey = "multiviewUsesTS"
    private static let playbackKey = "playbackSettings.v1"

    init() {
        let stored = UserDefaults.standard.string(forKey: Self.engineKey).flatMap(PlayerEngineKind.init(rawValue:))
        if let stored, stored.isAvailable {
            engine = stored
        } else {
            engine = .defaultEngine
        }
        // Before previewChoice: that one already has a value, so assigning it
        // uses self, which needs every stored property set first.
        playback = UserDefaults.standard.data(forKey: Self.playbackKey)
            .flatMap { try? JSONDecoder().decode(PlaybackSettings.self, from: $0) } ?? PlaybackSettings()
        multiviewIgnoresLimit = UserDefaults.standard.bool(forKey: Self.multiviewLimitKey)
        multiviewUsesTS = UserDefaults.standard.bool(forKey: Self.multiviewTSKey)
        previewChoice = UserDefaults.standard.string(forKey: Self.previewKey).flatMap(LivePreviewMode.init(rawValue:))
        sync.attach(self)
        multiview.attach(self)
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

    /// How many channels the multiview may play together: the account's
    /// connection count, four at most, unless the viewer chose to try more.
    var multiviewLimit: Int {
        Multiview.tileLimit(maxConnections: authInfo?.user.maxConnections, ignoringLimit: multiviewIgnoresLimit)
    }

    /// Whether the MPEG-TS trial can be offered: the build has KSPlayer and
    /// the panel offers TS, or doesn't say what it offers.
    var multiviewTSAvailable: Bool {
        let formats = authInfo?.user.allowedOutputFormats ?? []
        return KSPlayerSupport.isLinked && (formats.isEmpty || formats.contains("ts"))
    }

    /// True when the multiview's tiles are KSPlayer with MPEG-TS.
    var multiviewPlaysTS: Bool {
        multiviewUsesTS && multiviewTSAvailable
    }

    /// The connection count the panel reports, when it reports one.
    var reportedConnections: Int? {
        authInfo?.user.maxConnections
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

    /// The logins saved on this TV, in the order they were added.
    var accounts: [XtreamAccount] { book.accounts }
    /// The key of the account in use (`AccountBook.key`).
    var currentAccountKey: String? { book.currentKey }

    // MARK: Multiview across accounts

    /// Clients for other saved accounts, made when a channel first plays on one.
    private var otherClients: [String: XtreamClient] = [:]

    /// Where a new multiview channel plays: the account in use while it has
    /// connections to spare, then another saved account on the same server
    /// that the panel says has one free. Otherwise no account, with why each
    /// other account on the server wasn't used; the multiview then asks
    /// (`ConnectionAsk`), or says why under Try anyway.
    func pickMultiviewAccount(for tiles: MultiviewTiles) async -> (key: String?, refusals: [Multiview.BorrowRefusal]) {
        guard let client else { return (nil, []) }
        if let own = ownAccountRoom(for: tiles) {
            return (own, [])
        }
        let playing = tiles.countByAccount
        var refusals: [Multiview.BorrowRefusal] = []
        for other in book.others(onServerOf: client.account) {
            let key = AccountBook.key(other)
            // Asked now, not earlier: someone else may have started watching on it.
            guard let info = try? await self.client(forAccount: key)?.authenticate() else {
                refusals.append(.signInFailed)
                continue
            }
            authCache[key] = info
            if info.user.maxConnections == nil || info.user.activeConnections == nil {
                refusals.append(.notReported)
            } else if Multiview.hasFreeConnection(
                maxConnections: info.user.maxConnections,
                activeConnections: info.user.activeConnections,
                playingHere: playing[key, default: 0]
            ) {
                return (key, [])
            } else {
                refusals.append(.noneFree)
            }
        }
        return (nil, refusals)
    }

    /// The account in use, when it has connections to spare. Answered
    /// without asking the provider, so a channel can be added at once.
    func ownAccountRoom(for tiles: MultiviewTiles) -> String? {
        guard let client else { return nil }
        let key = AccountBook.key(client.account)
        let ownLimit = Multiview.ownLimit(maxConnections: authInfo?.user.maxConnections)
        return tiles.countByAccount[key, default: 0] < ownLimit ? key : nil
    }

    /// The client a multiview channel plays through. The account in use, or
    /// another saved one; nil for an account that has since been removed.
    func client(forAccount key: String?) -> XtreamClient? {
        guard let key, let client else { return self.client }
        if key == AccountBook.key(client.account) { return client }
        if let made = otherClients[key] { return made }
        guard let account = book.accounts.first(where: { AccountBook.key($0) == key }) else { return nil }
        let made = XtreamClient(account: account, userAgent: Self.userAgent)
        otherClients[key] = made
        return made
    }

    /// Other saved accounts on the same server as the one in use.
    var otherAccountsOnServer: [XtreamAccount] {
        guard let account = client?.account else { return [] }
        return book.others(onServerOf: account)
    }

    /// The last account details seen for a saved account, if any.
    func accountDetails(_ key: String) -> XtreamAuthInfo? {
        authCache[key]
    }

    /// Activates the saved account in use, or shows the login screen.
    func restore() async {
        guard phase == .launching else { return }
        book = AccountStore.loadBook()
        guard let account = book.current else {
            phase = .signedOut
            return
        }
        activate(account)
        await refreshAccountDetails()
    }

    /// Adds a login (or updates the saved one with the same server and
    /// username) and switches to it. Used by the login screen, Add an
    /// account and the phone page.
    func signIn(server: String, username: String, password: String) async throws {
        guard let serverURL = XtreamAccount.normalizeServer(server) else {
            throw XtreamError.invalidServerURL
        }
        let account = XtreamAccount(
            serverURL: serverURL,
            username: username.trimmingCharacters(in: .whitespacesAndNewlines),
            password: password
        )
        let info = try await XtreamClient(account: account, userAgent: Self.userAgent).authenticate()

        book.upsert(account)
        AccountStore.save(book)
        authCache[AccountBook.key(account)] = info
        // A client made with the old password, if this login was saved before.
        otherClients[AccountBook.key(account)] = nil
        activate(account)
        addingAccount = false
    }

    /// Switches to another saved account. Its details are refreshed in the
    /// background; until then the last ones seen for it are used.
    func switchTo(_ key: String) {
        guard key != book.currentKey, book.select(key), let account = book.current else { return }
        AccountStore.save(book)
        activate(account)
        Task { await refreshAccountDetails() }
    }

    /// Deletes a saved login and its continue-watching list. Its favorites,
    /// hidden categories and channel order are kept, so adding it again
    /// brings them back. Removing the account in use moves to the first
    /// one left, or to the login screen.
    func removeAccount(_ key: String) {
        let wasCurrent = key == book.currentKey
        book.remove(key)
        AccountStore.save(book)
        watchHistory.forget(accountKey: key)
        authCache[key] = nil
        otherClients[key] = nil
        // Its channels would otherwise play on the account in use.
        multiview.removeTiles(onAccount: key)
        guard wasCurrent else { return }
        if let next = book.current {
            activate(next)
            Task { await refreshAccountDetails() }
        } else {
            deactivate()
        }
    }

    /// Settings' button: removes the account in use.
    func signOut() {
        if let key = book.currentKey {
            removeAccount(key)
        }
    }

    /// One place that makes an account the one in use, so every store
    /// follows it.
    private func activate(_ account: XtreamAccount) {
        let key = AccountBook.key(account)
        watchHistory.activate(account: account)
        categoryVisibility.activate(account: account)
        channelOrder.activate(account: account)
        favorites.activate(account: account)
        // Its channels belong to the account that was in use.
        multiview.clear()
        client = XtreamClient(account: account, userAgent: Self.userAgent)
        // The last details seen for this account, never the previous one's.
        authInfo = authCache[key]
        accountWarning = nil
        phase = .signedIn
    }

    private func deactivate() {
        watchHistory.deactivate()
        // Hidden categories, channel order and favorites stay saved per account.
        categoryVisibility.deactivate()
        channelOrder.deactivate()
        favorites.deactivate()
        multiview.clear()
        client = nil
        authInfo = nil
        accountWarning = nil
        phase = .signedOut
    }

    /// Account details (connections, formats, expiry) for the account in
    /// use, without holding up the UI. A late answer for an account that is
    /// no longer in use is kept for later but not shown.
    private func refreshAccountDetails() async {
        guard let client else { return }
        let key = AccountBook.key(client.account)
        do {
            let info = try await client.authenticate()
            authCache[key] = info
            if self.client === client {
                authInfo = info
                accountWarning = nil
            }
        } catch {
            if self.client === client {
                accountWarning = error.localizedDescription
            }
        }
    }
}
