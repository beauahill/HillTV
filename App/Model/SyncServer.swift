import Darwin
import Foundation
import HillTVCore
import Network
import Observation

/// The small web server behind Settings > Sync and the login screen's
/// "Use your phone". It runs only while the Sync screen is open, on this
/// TV's address on the home network, and answers a phone that scanned the
/// QR code: sign this TV in, save its settings to the phone, or load a
/// settings file into it.
///
/// Nothing is hosted anywhere: the phone talks to the TV directly. The page
/// is plain http, the same as most providers' own addresses, so the login a
/// phone sends travels over the home Wi-Fi unencrypted. Browsers give the
/// Web Crypto API only to secure (https) pages (MDN, checked Nov 2026), so
/// the page can't add encryption of its own.
///
/// Owned by `AppModel`, not by a screen, because signing in rebuilds every
/// screen below the Sync cover.
@MainActor
@Observable
final class SyncServer {
    enum Status: Equatable {
        case stopped
        case starting
        case ready(url: URL, address: String, code: String)
        case failed(String)
    }

    private(set) var status: Status = .stopped
    /// What the phone did, newest first, for the TV to show.
    private(set) var activity: [String] = []
    /// Too many wrong codes: requests are refused until the screen reopens.
    private(set) var isLocked = false

    private weak var app: AppModel?
    private var listener: NWListener?
    private var gate = SyncGate(code: "")
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    func attach(_ app: AppModel) {
        self.app = app
    }

    // MARK: Running

    func start() {
        stop()
        guard let address = Self.localAddress() else {
            status = .failed("This Apple TV doesn't have an address on a network. Connect it to Wi-Fi or Ethernet and try again.")
            return
        }
        let code = SyncGate.makeCode()
        gate = SyncGate(code: code)
        isLocked = false
        activity = []
        status = .starting
        do {
            let listener = try NWListener(using: .tcp, on: .any)
            listener.stateUpdateHandler = { [weak self] state in
                MainActor.assumeIsolated {
                    self?.listenerChanged(state, address: address, code: code)
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                MainActor.assumeIsolated {
                    self?.accept(connection)
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            status = .failed("Sync couldn't start: \(error.localizedDescription)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        for connection in connections.values {
            connection.cancel()
        }
        connections = [:]
        status = .stopped
    }

    private func listenerChanged(_ state: NWListener.State, address: String, code: String) {
        switch state {
        case .ready:
            guard let port = listener?.port?.rawValue,
                  let url = URL(string: "http://\(address):\(port)/?code=\(code)") else {
                status = .failed("Sync couldn't find which port it was given.")
                return
            }
            status = .ready(url: url, address: "http://\(address):\(port)", code: code)
        case .failed(let error):
            status = .failed("Sync stopped: \(error.localizedDescription)")
        default:
            break
        }
    }

    private func note(_ text: String) {
        activity.insert(text, at: 0)
        if activity.count > 5 { activity.removeLast() }
    }

    // MARK: Connections

    private func accept(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled:
                MainActor.assumeIsolated {
                    _ = self?.connections.removeValue(forKey: id)
                }
            default:
                break
            }
        }
        connection.start(queue: .main)
        receive(on: connection, reader: SyncRequestReader())
        // A connection that never finishes its request is dropped.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            connection.cancel()
            self?.connections[id] = nil
        }
    }

    private func receive(on connection: NWConnection, reader: SyncRequestReader) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            MainActor.assumeIsolated {
                guard let self else {
                    connection.cancel()
                    return
                }
                var reader = reader
                if let data, !data.isEmpty {
                    switch reader.add(data) {
                    case .needMore:
                        break
                    case .done(let request):
                        Task { await self.handle(request, on: connection) }
                        return
                    case .failed(let status):
                        self.send(.message("The TV couldn't read that request.", ok: false, status: status), on: connection)
                        return
                    }
                }
                if isComplete || error != nil {
                    connection.cancel()
                    return
                }
                self.receive(on: connection, reader: reader)
            }
        }
    }

    private func send(_ response: SyncResponse, on connection: NWConnection) {
        connection.send(content: response.serialized(), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    // MARK: Requests

    private func handle(_ request: SyncRequest, on connection: NWConnection) async {
        let route = gate.route(request)
        // The screen shows the lock in red on its own; the activity list is
        // only for what the phone did, each line with a tick.
        isLocked = gate.isLocked

        let response: SyncResponse
        switch route {
        case .page:
            response = SyncResponse(status: 200, contentType: "text/html; charset=utf-8", body: Data(SyncPage.html.utf8))
        case .reply(let reply):
            response = reply
        case .state:
            response = stateResponse()
        case .signIn(let server, let username, let password):
            response = await signIn(server: server, username: username, password: password)
        case .downloadSettings:
            response = downloadResponse()
        case .uploadSettings(let data, let choices):
            response = upload(data, choices: choices)
        }
        send(response, on: connection)
    }

    private var account: XtreamAccount? {
        app?.client?.account
    }

    private func stateResponse() -> SyncResponse {
        if activity.isEmpty { note("A phone opened the page.") }
        guard let account else { return .json(["signedIn": false]) }
        // Server and username only. The password never leaves the TV.
        return .json(["signedIn": true, "server": account.serverURL.absoluteString, "username": account.username])
    }

    private func signIn(server: String, username: String, password: String) async -> SyncResponse {
        guard let app else { return .message("The TV isn't ready.", ok: false, status: 500) }
        do {
            try await app.signIn(server: server, username: username, password: password)
            note("Signed in as \(username).")
            return .message("Signed in. The TV is loading your channels.", ok: true)
        } catch {
            return .message(error.localizedDescription, ok: false)
        }
    }

    private func downloadResponse() -> SyncResponse {
        guard let app, let account else {
            return .message("Sign this TV in first.", ok: false, status: 409)
        }
        let file = SyncSettings(
            account: .init(server: account.serverURL.absoluteString, username: account.username),
            hiddenCategories: app.categoryVisibility.visibility,
            favorites: app.favorites.channels,
            channelOrder: app.channelOrder.order
        )
        note("Settings saved to the phone.")
        return SyncResponse(
            status: 200,
            contentType: "application/json; charset=utf-8",
            body: file.encoded(),
            extraHeaders: [("Content-Disposition", "attachment; filename=\"hilltv-settings.json\"")]
        )
    }

    private func upload(_ data: Data, choices: SyncSettings.Choices) -> SyncResponse {
        guard let app, let account else {
            return .message("Sign this TV in first.", ok: false, status: 409)
        }
        let file: SyncSettings
        do {
            file = try SyncSettings.read(data)
        } catch {
            return .message(error.localizedDescription, ok: false)
        }
        guard SyncSettings.sameServer(file.account.server, account.serverURL.absoluteString) else {
            return .message(
                "That file is from a different server (\(file.account.server)). Its categories wouldn't match this TV's.",
                ok: false,
                status: 409
            )
        }
        if choices.hiddenCategories { app.categoryVisibility.replace(with: file.hiddenCategories) }
        if choices.favorites { app.favorites.replace(with: file.favorites) }
        if choices.channelOrder { app.channelOrder.replace(with: file.channelOrder) }
        let summary = file.summary(applying: choices)
        note(summary)
        return .message(summary, ok: true)
    }

    // MARK: Address

    /// This TV's IPv4 address on the home network. Wired and Wi-Fi
    /// interfaces are tried first; self-assigned addresses are skipped.
    static func localAddress() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var candidates: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard let address = entry.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else {
                continue
            }
            let text = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            guard !text.hasPrefix("169.254.") else { continue }
            candidates.append((String(cString: entry.ifa_name), text))
        }
        return (candidates.first { $0.name.hasPrefix("en") } ?? candidates.first)?.address
    }
}
