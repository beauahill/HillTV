import XCTest
@testable import HillTVCore

final class SyncSettingsTests: XCTestCase {
    private func sample() -> SyncSettings {
        var hidden = CategoryVisibility()
        hidden.setHidden(true, ids: ["1", "2"], in: .live)
        hidden.setHidden(true, ids: ["9"], in: .movies)
        var order = ChannelOrder()
        order.move(3, to: 0, in: [1, 2, 3], key: "12")
        return SyncSettings(
            account: .init(server: "http://line.example.com:8080", username: "viewer"),
            hiddenCategories: hidden,
            favorites: [LiveStream(id: 7, name: "US | CBS"), LiveStream(id: 8, name: "ESPN")],
            channelOrder: order
        )
    }

    func testRoundTrip() throws {
        let file = sample()
        let read = try SyncSettings.read(file.encoded())
        XCTAssertEqual(read, file)
        XCTAssertTrue(read.hiddenCategories.isHidden("2", in: .live))
        XCTAssertEqual(read.favorites.map(\.id), [7, 8])
    }

    func testNeverHoldsThePassword() {
        let text = String(decoding: sample().encoded(), as: UTF8.self)
        XCTAssertFalse(text.lowercased().contains("password"))
        XCTAssertTrue(text.contains("\"format\" : \"hilltv.settings\""))
    }

    func testRefusesOtherFiles() {
        XCTAssertThrowsError(try SyncSettings.read(Data("{}".utf8))) {
            XCTAssertEqual($0 as? SyncSettings.ReadError, .notASettingsFile)
        }
        XCTAssertThrowsError(try SyncSettings.read(Data("not json".utf8)))
        XCTAssertThrowsError(try SyncSettings.read(Data(#"{"format":"other","account":{"server":"a","username":"b"}}"#.utf8)))
        XCTAssertThrowsError(try SyncSettings.read(Data(repeating: 32, count: SyncSettings.maximumBytes + 1))) {
            XCTAssertEqual($0 as? SyncSettings.ReadError, .tooLarge)
        }
    }

    func testNewerVersionIsRefusedWithAReason() {
        let json = #"{"format":"hilltv.settings","version":2,"account":{"server":"http://a","username":"b"}}"#
        XCTAssertThrowsError(try SyncSettings.read(Data(json.utf8))) {
            XCTAssertEqual($0 as? SyncSettings.ReadError, .newerVersion(2))
        }
    }

    func testABadSectionLeavesTheOthers() throws {
        let json = #"""
        {"format":"hilltv.settings","version":1,
         "account":{"server":"http://line.example.com","username":"viewer"},
         "hiddenCategories":"garbage",
         "favorites":[{"stream_id":5,"name":"Good"},{"name":"no id"}],
         "channelOrder":{"12":[3,1]}}
        """#
        let read = try SyncSettings.read(Data(json.utf8))
        XCTAssertEqual(read.hiddenCategories, CategoryVisibility())
        XCTAssertEqual(read.favorites.map(\.id), [5])
        XCTAssertEqual(read.channelOrder.lists["12"], [3, 1])
    }

    func testSameServer() {
        XCTAssertTrue(SyncSettings.sameServer("http://Line.Example.com:8080/", "line.example.com:8080"))
        XCTAssertTrue(SyncSettings.sameServer("http://line.example.com:8080/player_api.php", "http://line.example.com:8080"))
        XCTAssertFalse(SyncSettings.sameServer("http://line.example.com:8080", "http://line.example.com:8000"))
        XCTAssertFalse(SyncSettings.sameServer("http://a.example.com", "http://b.example.com"))
        XCTAssertFalse(SyncSettings.sameServer("", "http://a.example.com"))
    }

    func testSummary() {
        let file = sample()
        XCTAssertEqual(file.summary(applying: .init()), "Loaded 3 hidden categories.")
        XCTAssertEqual(
            file.summary(applying: .init(hiddenCategories: true, favorites: true, channelOrder: true)),
            "Loaded 3 hidden categories, 2 favorites and the order of 1 channel list."
        )
        XCTAssertEqual(file.summary(applying: .init(hiddenCategories: false)), "Nothing was loaded.")
    }
}

final class SyncHTTPTests: XCTestCase {
    private func read(_ chunks: [String]) -> SyncRequestReader.Result {
        var reader = SyncRequestReader()
        var result = SyncRequestReader.Result.needMore
        for chunk in chunks {
            result = reader.add(Data(chunk.utf8))
        }
        return result
    }

    func testRequestInPieces() {
        let result = read([
            "POST /api/sign-in?code=123456 HT",
            "TP/1.1\r\nHost: 192.168.1.20\r\nContent-Le",
            "ngth: 11\r\nX-HillTV-Code: 123456\r\n\r\nhello",
            " world",
        ])
        guard case .done(let request) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(request.method, "POST")
        XCTAssertEqual(request.path, "/api/sign-in")
        XCTAssertEqual(request.query["code"], "123456")
        XCTAssertEqual(request.headers["x-hilltv-code"], "123456")
        XCTAssertEqual(String(decoding: request.body, as: UTF8.self), "hello world")
    }

    func testWaitsForTheWholeBody() {
        XCTAssertEqual(read(["POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"]), .needMore)
        XCTAssertEqual(read(["GET / HTTP/1.1\r\nHost: a"]), .needMore)
    }

    func testBadRequests() {
        XCTAssertEqual(read(["NONSENSE\r\n\r\n"]), .failed(status: 400))
        XCTAssertEqual(read(["GET / HTTP/1.1\r\nContent-Length: -4\r\n\r\n"]), .failed(status: 400))
        XCTAssertEqual(read(["POST / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n"]), .failed(status: 413))
        XCTAssertEqual(read([String(repeating: "a", count: 17_000)]), .failed(status: 431))
    }

    func testQueryDecoding() {
        XCTAssertEqual(SyncRequestReader.parseQuery("a=1&b=two+words&c=%2Fx&d"), ["a": "1", "b": "two words", "c": "/x", "d": ""])
    }

    func testResponseBytes() {
        let response = SyncResponse(status: 200, contentType: "text/plain", body: Data("hi".utf8), extraHeaders: [("X-A", "b")])
        let text = String(decoding: response.serialized(), as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("HTTP/1.1 200 OK\r\n"))
        XCTAssertTrue(text.contains("Content-Length: 2\r\n"))
        XCTAssertTrue(text.contains("Connection: close\r\n"))
        XCTAssertTrue(text.contains("Cache-Control: no-store\r\n"))
        XCTAssertTrue(text.contains("X-A: b\r\n"))
        XCTAssertTrue(text.hasSuffix("\r\n\r\nhi"))
    }
}

final class SyncGateTests: XCTestCase {
    private func request(_ method: String, _ path: String, query: [String: String] = [:], headers: [String: String] = [:], body: String = "") -> SyncRequest {
        SyncRequest(method: method, path: path, query: query, headers: headers, body: Data(body.utf8))
    }

    func testThePageNeedsNoCode() {
        var gate = SyncGate(code: "123456")
        XCTAssertEqual(gate.route(request("GET", "/")), .page)
        XCTAssertEqual(gate.route(request("GET", "/favicon.ico")), .reply(.message("Not found.", ok: false, status: 404)))
        XCTAssertEqual(gate.misses, 0)
    }

    func testEveryAPIRequestNeedsTheCode() {
        var gate = SyncGate(code: "123456")
        guard case .reply(let refused) = gate.route(request("GET", "/api/state")) else { return XCTFail() }
        XCTAssertEqual(refused.status, 403)
        XCTAssertEqual(gate.route(request("GET", "/api/state", headers: ["x-hilltv-code": "123456"])), .state)
        XCTAssertEqual(gate.route(request("GET", "/api/settings", query: ["code": "123456"])), .downloadSettings)
    }

    func testLocksAfterTooManyWrongCodes() {
        var gate = SyncGate(code: "123456")
        for _ in 1..<SyncGate.allowedMisses {
            _ = gate.route(request("GET", "/api/state", query: ["code": "000000"]))
        }
        XCTAssertFalse(gate.isLocked)
        guard case .reply(let last) = gate.route(request("GET", "/api/state", query: ["code": "111111"])) else { return XCTFail() }
        XCTAssertEqual(last.status, 423)
        XCTAssertTrue(gate.isLocked)
        // Even the right code is refused now.
        guard case .reply(let locked) = gate.route(request("GET", "/api/state", query: ["code": "123456"])) else { return XCTFail() }
        XCTAssertEqual(locked.status, 423)
        // The page still loads, so it can say why.
        XCTAssertEqual(gate.route(request("GET", "/")), .page)
    }

    func testSignIn() {
        var gate = SyncGate(code: "1")
        let ok = gate.route(request("POST", "/api/sign-in", query: ["code": "1"],
                                    body: #"{"server":" line.example.com ","username":"viewer","password":"p+ss w"}"#))
        XCTAssertEqual(ok, .signIn(server: "line.example.com", username: "viewer", password: "p+ss w"))
        guard case .reply(let missing) = gate.route(request("POST", "/api/sign-in", query: ["code": "1"], body: #"{"server":"a"}"#)) else { return XCTFail() }
        XCTAssertEqual(missing.status, 400)
        guard case .reply(let wrongMethod) = gate.route(request("GET", "/api/sign-in", query: ["code": "1"])) else { return XCTFail() }
        XCTAssertEqual(wrongMethod.status, 405)
    }

    func testUploadChoices() {
        var gate = SyncGate(code: "1")
        XCTAssertEqual(
            gate.route(request("POST", "/api/settings", query: ["code": "1", "hidden": "1", "favorites": "0", "order": "1"], body: "{}")),
            .uploadSettings(Data("{}".utf8), .init(hiddenCategories: true, favorites: false, channelOrder: true))
        )
        guard case .reply(let none) = gate.route(request("POST", "/api/settings", query: ["code": "1"], body: "{}")) else { return XCTFail() }
        XCTAssertEqual(none.status, 400)
    }

    func testCodeIsSixDigits() {
        for _ in 0..<50 {
            let code = SyncGate.makeCode()
            XCTAssertEqual(code.count, 6)
            XCTAssertTrue(code.allSatisfy(\.isNumber))
        }
    }

    func testPageWritesNoValuesAsHTML() {
        XCTAssertFalse(SyncPage.html.contains("innerHTML"))
        XCTAssertFalse(SyncPage.html.contains("document.write"))
        XCTAssertTrue(SyncPage.html.contains("textContent"))
    }
}
