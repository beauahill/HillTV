import XCTest
@testable import HillTVCore

final class WatchHistoryTests: XCTestCase {
    private func entry(_ id: String, position: Double, duration: Double? = 6000, kind: WatchEntry.Kind = .movie) -> WatchEntry {
        WatchEntry(kind: kind, streamID: id, containerExtension: "mkv", title: "Item \(id)", position: position, duration: duration)
    }

    func testRecordsAndMovesRewatchedToFront() {
        var history = WatchHistory()
        history.record(entry("1", position: 100))
        history.record(entry("2", position: 200))
        XCTAssertEqual(history.entries.map(\.streamID), ["2", "1"])

        history.record(entry("1", position: 300))
        XCTAssertEqual(history.entries.map(\.streamID), ["1", "2"])
        XCTAssertEqual(history.entry(id: "movie-1")?.position, 300)
    }

    func testIgnoresBarelyStartedAndKeepsEarlierProgress() {
        var history = WatchHistory()
        history.record(entry("1", position: 10))
        XCTAssertTrue(history.entries.isEmpty)

        history.record(entry("1", position: 900))
        history.record(entry("1", position: 5))
        XCTAssertEqual(history.entry(id: "movie-1")?.position, 900)
    }

    func testFinishedItemsLeaveTheList() {
        var history = WatchHistory()
        history.record(entry("1", position: 900))
        history.record(entry("1", position: 5800))
        XCTAssertNil(history.entry(id: "movie-1"))
    }

    func testUnknownOrBrokenDurationIsKeptWithoutProgress() {
        var history = WatchHistory()
        history.record(entry("1", position: 900, duration: nil))
        history.record(entry("2", position: 900, duration: .nan))
        history.record(entry("3", position: 900, duration: .infinity))
        history.record(entry("4", position: .nan))
        XCTAssertEqual(history.entries.map(\.streamID), ["3", "2", "1"])
        XCTAssertTrue(history.entries.allSatisfy { $0.duration == nil && $0.progress == nil })
    }

    func testTrimsToMaxEntries() {
        var history = WatchHistory()
        for index in 0..<(WatchHistory.maxEntries + 5) {
            history.record(entry(String(index), position: 100))
        }
        XCTAssertEqual(history.entries.count, WatchHistory.maxEntries)
        XCTAssertEqual(history.entries.first?.streamID, String(WatchHistory.maxEntries + 4))
    }

    func testMoviesAndEpisodesWithSameIDAreSeparate() {
        var history = WatchHistory()
        history.record(entry("7", position: 100, kind: .movie))
        history.record(entry("7", position: 100, kind: .episode))
        XCTAssertEqual(history.entries.count, 2)
    }

    func testRoundTripsThroughJSON() throws {
        var history = WatchHistory()
        history.record(WatchEntry(kind: .episode, streamID: "9001", containerExtension: "mp4", title: "Show",
                                  subtitle: "S1E2", artworkURL: URL(string: "http://img.example.com/c.jpg"),
                                  position: 120, duration: 2400))
        let data = try JSONEncoder().encode(history)
        XCTAssertEqual(try JSONDecoder().decode(WatchHistory.self, from: data), history)
    }

    func testResumeURLsFromIDs() {
        let account = XtreamAccount(serverURL: URL(string: "http://line.example.com:8080")!, username: "u", password: "p+w")
        let client = XtreamClient(account: account)
        XCTAssertEqual(client.movieURL(streamID: 5001, containerExtension: "mkv").absoluteString,
                       "http://line.example.com:8080/movie/u/p+w/5001.mkv")
        XCTAssertEqual(client.episodeURL(episodeID: "9001", containerExtension: "mp4").absoluteString,
                       "http://line.example.com:8080/series/u/p+w/9001.mp4")
    }
}
