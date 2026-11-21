import XCTest
@testable import HillTVCore

final class GuideWindowTests: XCTestCase {
    private let base: TimeInterval = 1_800_000_000 // a half-hour boundary

    private func entries(_ spans: [(String, TimeInterval, TimeInterval)]) throws -> [EPGEntry] {
        let rows = spans.map { title, start, end in
            #"{"id":"\#(title)","title":"\#(title)","start_timestamp":"\#(Int(base + start))","stop_timestamp":"\#(Int(base + end))"}"#
        }
        let json = #"{"epg_listings":[\#(rows.joined(separator: ","))]}"#
        return try JSONDecoder().decode(ShortEPGResponse.self, from: Data(json.utf8)).listings
    }

    private var window: GuideWindow {
        GuideWindow(start: Date(timeIntervalSince1970: base), end: Date(timeIntervalSince1970: base + 3 * 3600))
    }

    func testStartingFloorsToHalfHour() {
        let now = Date(timeIntervalSince1970: base + 17 * 60)
        let window = GuideWindow.starting(at: now, hours: 3)
        XCTAssertEqual(window.start, Date(timeIntervalSince1970: base))
        XCTAssertEqual(window.duration, 3 * 3600)
        XCTAssertEqual(window.slots().count, 6)
    }

    func testCellsClipFillGapsAndTileTheWindow() throws {
        let listings = try entries([
            ("early", -1800, 1800),     // spans the window start
            ("mid", 3600, 5400),        // gap before it
            ("late", 9000, 14400),      // runs past the end
            ("outside", 20000, 21000),  // fully outside
        ])
        let cells = window.cells(for: listings)
        XCTAssertEqual(cells.map { $0.entry?.title }, ["early", nil, "mid", nil, "late"])
        XCTAssertEqual(cells.first?.start, window.start)
        XCTAssertEqual(cells.last?.end, window.end)
        XCTAssertEqual(cells.reduce(0) { $0 + $1.width }, 1, accuracy: 0.0001)
        XCTAssertEqual(cells[0].width, 1800 / (3 * 3600), accuracy: 0.0001)
    }

    func testOverlapsAreTrimmedAndZeroLengthDropped() throws {
        let listings = try entries([
            ("a", 0, 3600),
            ("b", 1800, 5400),   // overlaps a
            ("zero", 6000, 6000),
        ])
        let cells = window.cells(for: listings)
        XCTAssertEqual(cells.map { $0.entry?.title }, ["a", "b", nil])
        XCTAssertEqual(cells[1].start, Date(timeIntervalSince1970: base + 3600))
    }

    func testNoListingsGivesOneFocusableCell() {
        let cells = window.cells(for: [])
        XCTAssertEqual(cells.count, 1)
        XCTAssertNil(cells[0].entry)
        XCTAssertEqual(cells[0].width, 1, accuracy: 0.0001)
    }

    func testFractionClamps() {
        XCTAssertEqual(window.fraction(of: Date(timeIntervalSince1970: base - 100)), 0)
        XCTAssertEqual(window.fraction(of: Date(timeIntervalSince1970: base + 5400)), 0.5, accuracy: 0.0001)
        XCTAssertEqual(window.fraction(of: Date(timeIntervalSince1970: base + 99999)), 1)
    }
}
