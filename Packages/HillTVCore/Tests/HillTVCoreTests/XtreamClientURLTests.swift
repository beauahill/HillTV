import XCTest
@testable import HillTVCore

final class XtreamClientURLTests: XCTestCase {
    func testNormalizeServer() {
        XCTAssertEqual(XtreamAccount.normalizeServer("line.example.com:8080")?.absoluteString, "http://line.example.com:8080")
        XCTAssertEqual(XtreamAccount.normalizeServer(" http://line.example.com:8080/ ")?.absoluteString, "http://line.example.com:8080")
        XCTAssertEqual(XtreamAccount.normalizeServer("https://host.tv/panel/player_api.php?username=a&password=b")?.absoluteString,
                       "https://host.tv/panel")
        XCTAssertEqual(XtreamAccount.normalizeServer("HTTP://host.tv/get.php")?.absoluteString, "HTTP://host.tv")
        XCTAssertNil(XtreamAccount.normalizeServer(""))
        XCTAssertNil(XtreamAccount.normalizeServer("   "))
    }

    func testAPIURLEncodesPlusAndAmpersand() throws {
        let client = makeClient(password: "a+b&c d")
        let url = client.apiURL(action: "get_live_streams", query: [URLQueryItem(name: "category_id", value: "5")])
        let query = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery)
        XCTAssertTrue(query.contains("password=a%2Bb%26c%20d"), query)
        XCTAssertTrue(query.contains("action=get_live_streams"))
        XCTAssertTrue(query.contains("category_id=5"))
        XCTAssertEqual(url.path, "/player_api.php")
    }

    func testStreamURLs() {
        let client = makeClient(password: "p/ss?#")
        XCTAssertEqual(client.liveURL(streamID: 101, format: .hls).absoluteString,
                       "http://line.example.com:8080/live/beau/p%2Fss%3F%23/101.m3u8")
        XCTAssertEqual(client.liveURL(streamID: 101, format: .mpegts).pathExtension, "ts")
    }

    func testBasePathIsKept() throws {
        let account = XtreamAccount(serverURL: try XCTUnwrap(XtreamAccount.normalizeServer("https://host.tv/panel/")),
                                    username: "u", password: "p")
        let client = XtreamClient(account: account)
        XCTAssertEqual(client.apiURL(action: nil).path, "/panel/player_api.php")
        XCTAssertEqual(client.liveURL(streamID: 1, format: .hls).absoluteString, "https://host.tv/panel/live/u/p/1.m3u8")
        XCTAssertEqual(client.xmltvURL.path, "/panel/xmltv.php")
    }

    private func makeClient(password: String) -> XtreamClient {
        XtreamClient(account: XtreamAccount(serverURL: URL(string: "http://line.example.com:8080")!, username: "beau", password: password))
    }
}
