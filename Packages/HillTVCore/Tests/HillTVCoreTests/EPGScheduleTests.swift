import XCTest
@testable import HillTVCore

final class EPGScheduleTests: XCTestCase {
    private let base: TimeInterval = 1_800_000_000

    private func at(_ offset: TimeInterval) -> Date {
        Date(timeIntervalSince1970: base + offset)
    }

    private func schedule(_ spans: [(String, TimeInterval, TimeInterval)]) throws -> EPGSchedule {
        let rows = spans.map { title, start, end in
            #"{"id":"\#(title)","title":"\#(title)","start_timestamp":"\#(Int(base + start))","stop_timestamp":"\#(Int(base + end))"}"#
        }
        let json = #"{"epg_listings":[\#(rows.joined(separator: ","))]}"#
        let listings = try JSONDecoder().decode(ShortEPGResponse.self, from: Data(json.utf8)).listings
        return EPGSchedule(listings)
    }

    func testSortsAndDropsZeroLengthEntries() throws {
        let result = try schedule([
            ("late", 3600, 7200),
            ("broken", 600, 600),
            ("early", 0, 3600),
        ])
        XCTAssertEqual(result.entries.map(\.title), ["early", "late"])
    }

    func testCurrentAndNext() throws {
        let result = try schedule([
            ("news", 0, 3600),
            ("weather", 3600, 5400),
            ("sport", 5400, 9000),
        ])
        XCTAssertEqual(result.current(at: at(1800))?.title, "news")
        XCTAssertEqual(result.next(after: at(1800))?.title, "weather")
        // A boundary belongs to the programme that is starting.
        XCTAssertEqual(result.current(at: at(3600))?.title, "weather")
        XCTAssertEqual(result.next(after: at(3600))?.title, "sport")
        XCTAssertNil(result.current(at: at(9000)))
        XCTAssertNil(result.next(after: at(9000)))
    }

    func testCurrentIsNilInsideAGap() throws {
        let result = try schedule([
            ("news", 0, 1800),
            ("sport", 3600, 7200),
        ])
        XCTAssertNil(result.current(at: at(2400)))
        XCTAssertEqual(result.next(after: at(2400))?.title, "sport")
    }

    func testUpcomingStartsWithWhatIsOnAndRespectsLimit() throws {
        let result = try schedule([
            ("aired", -3600, 0),
            ("news", 0, 3600),
            ("weather", 3600, 5400),
            ("sport", 5400, 9000),
        ])
        XCTAssertEqual(result.upcoming(from: at(60), limit: 2).map(\.title), ["news", "weather"])
        XCTAssertEqual(result.upcoming(from: at(60), limit: 10).map(\.title), ["news", "weather", "sport"])
        XCTAssertEqual(result.upcoming(from: at(60), limit: 0), [])
        XCTAssertEqual(result.upcoming(from: at(60), limit: -1), [])
    }

    func testCovers() throws {
        let result = try schedule([("news", 0, 3600)])
        XCTAssertTrue(result.covers(at(10)))
        XCTAssertFalse(result.covers(at(3600)))
        XCTAssertFalse(EPGSchedule.empty.covers(at(0)))
    }

    func testMinutesLeftRoundsUpAndNeverGoesNegative() throws {
        let result = try schedule([("news", 0, 3600)])
        let news = try XCTUnwrap(result.entries.first)
        XCTAssertEqual(EPGSchedule.minutesLeft(in: news, at: at(0)), 60)
        XCTAssertEqual(EPGSchedule.minutesLeft(in: news, at: at(3541)), 1)
        XCTAssertEqual(EPGSchedule.minutesLeft(in: news, at: at(3600)), 0)
        XCTAssertEqual(EPGSchedule.minutesLeft(in: news, at: at(4000)), 0)
    }

    func testNeedsFetchWhenNeverFetched() {
        XCTAssertTrue(EPGSchedule.needsFetch(schedule: nil, fetchedAt: nil, now: at(0)))
        XCTAssertTrue(EPGSchedule.needsFetch(schedule: EPGSchedule.empty, fetchedAt: nil, now: at(0)))
    }

    func testFreshListingsAreNotRefetched() throws {
        let result = try schedule([("news", 0, 3600)])
        XCTAssertFalse(EPGSchedule.needsFetch(schedule: result, fetchedAt: at(-7200), now: at(1800)))
    }

    func testEmptyListingsWaitForTheRetryInterval() {
        XCTAssertFalse(EPGSchedule.needsFetch(schedule: EPGSchedule.empty, fetchedAt: at(0), now: at(599)))
        XCTAssertTrue(EPGSchedule.needsFetch(schedule: EPGSchedule.empty, fetchedAt: at(0), now: at(600)))
    }

    func testAiredListingsAreRefetchedAfterTheRetryInterval() throws {
        let result = try schedule([("news", 0, 3600)])
        // Just fetched and already stale: the panel has nothing newer yet.
        XCTAssertFalse(EPGSchedule.needsFetch(schedule: result, fetchedAt: at(3600), now: at(3700)))
        XCTAssertTrue(EPGSchedule.needsFetch(schedule: result, fetchedAt: at(0), now: at(3700)))
    }
}
