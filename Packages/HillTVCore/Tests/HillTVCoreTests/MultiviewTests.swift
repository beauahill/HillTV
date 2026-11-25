import XCTest
@testable import HillTVCore

final class MultiviewTests: XCTestCase {
    func testTileLimitFollowsTheAccount() {
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 1), 1)
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 2), 2)
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 3), 3)
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 10), 4)
        // Nonsense from the panel still leaves the one channel a player needs.
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 0), 1)
        XCTAssertEqual(Multiview.tileLimit(maxConnections: -1), 1)
        // A panel that doesn't say is not treated as one connection.
        XCTAssertEqual(Multiview.tileLimit(maxConnections: nil), 4)
    }

    func testViewerCanChooseToTryPastTheReportedLimit() {
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 1, ignoringLimit: true), 4)
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 2, ignoringLimit: true), 4)
        XCTAssertEqual(Multiview.tileLimit(maxConnections: nil, ignoringLimit: true), 4)
        XCTAssertTrue(Multiview.isAvailable(maxConnections: 1, ignoringLimit: true))
        // Off is the default, and the account's count stands.
        XCTAssertEqual(Multiview.tileLimit(maxConnections: 1, ignoringLimit: false), 1)
    }

    func testAvailability() {
        XCTAssertFalse(Multiview.isAvailable(maxConnections: 1))
        XCTAssertTrue(Multiview.isAvailable(maxConnections: 2))
        XCTAssertTrue(Multiview.isAvailable(maxConnections: nil))
    }

    func testGrid() {
        XCTAssertTrue(Multiview.grid(for: 0) == (1, 1))
        XCTAssertTrue(Multiview.grid(for: 1) == (1, 1))
        XCTAssertTrue(Multiview.grid(for: 2) == (2, 1))
        XCTAssertTrue(Multiview.grid(for: 3) == (2, 2))
        XCTAssertTrue(Multiview.grid(for: 4) == (2, 2))
    }

    func testOneCellFillsA16By9Screen() {
        XCTAssertEqual(
            Multiview.frames(cells: 1, width: 1920, height: 1080, spacing: 8),
            [.init(x: 0, y: 0, width: 1920, height: 1080)]
        )
    }

    func testTwoCellsSitSideBySideInTheMiddle() {
        let frames = Multiview.frames(cells: 2, width: 1920, height: 1080, spacing: 8)
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].width, 956)
        XCTAssertEqual(frames[0].height, 537.75)
        XCTAssertEqual(frames[0].x, 0)
        XCTAssertEqual(frames[1].x, 964)
        // Centered top to bottom.
        XCTAssertEqual(frames[0].y, (1080 - 537.75) / 2)
        XCTAssertEqual(frames[0].y, frames[1].y)
    }

    func testFourCellsMakeAGrid() {
        let frames = Multiview.frames(cells: 4, width: 1920, height: 1080, spacing: 8)
        XCTAssertEqual(frames.count, 4)
        for frame in frames {
            XCTAssertEqual(frame.width / frame.height, 16.0 / 9.0, accuracy: 0.001)
            XCTAssertGreaterThanOrEqual(frame.x, 0)
            XCTAssertLessThanOrEqual(frame.x + frame.width, 1920.001)
            XCTAssertLessThanOrEqual(frame.y + frame.height, 1080.001)
        }
        XCTAssertEqual(frames[0].y, frames[1].y)
        XCTAssertEqual(frames[0].x, frames[2].x)
        XCTAssertGreaterThan(frames[1].x, frames[0].x + frames[0].width)
        XCTAssertGreaterThan(frames[2].y, frames[0].y + frames[0].height)
        // Three cells use the same grid and leave the last place empty.
        XCTAssertEqual(Multiview.frames(cells: 3, width: 1920, height: 1080, spacing: 8), Array(frames.prefix(3)))
    }

    func testFramesWithNothingToLayOut() {
        XCTAssertEqual(Multiview.frames(cells: 0, width: 1920, height: 1080, spacing: 8), [])
        XCTAssertEqual(Multiview.frames(cells: 2, width: 0, height: 0, spacing: 8), [])
        // More than the grid holds: only the grid's places come back.
        XCTAssertEqual(Multiview.frames(cells: 9, width: 1920, height: 1080, spacing: 8).count, 4)
    }

    func testPictureCapShrinksAsChannelsAreAdded() {
        XCTAssertNil(Multiview.pictureCap(tiles: 1, fullScreen: false))
        XCTAssertTrue(Multiview.pictureCap(tiles: 2, fullScreen: false)! == (1280, 720))
        XCTAssertTrue(Multiview.pictureCap(tiles: 3, fullScreen: false)! == (960, 540))
        XCTAssertTrue(Multiview.pictureCap(tiles: 4, fullScreen: false)! == (960, 540))
        // A tile shown full screen gets the full picture back.
        XCTAssertNil(Multiview.pictureCap(tiles: 4, fullScreen: true))
    }

    func testTiles() {
        var tiles = MultiviewTiles()
        XCTAssertTrue(tiles.isEmpty)
        XCTAssertEqual(tiles.add(LiveStream(id: 1, name: "One"), limit: 2), .added)
        XCTAssertEqual(tiles.add(LiveStream(id: 1, name: "One"), limit: 2), .alreadyThere)
        XCTAssertEqual(tiles.add(LiveStream(id: 2, name: "Two"), limit: 2), .added)
        XCTAssertEqual(tiles.add(LiveStream(id: 3, name: "Three"), limit: 2), .full)
        // Already there wins over full, so opening one that is playing never reads as an error.
        XCTAssertEqual(tiles.add(LiveStream(id: 2, name: "Two"), limit: 2), .alreadyThere)
        XCTAssertEqual(tiles.streams.map(\.id), [1, 2])
        XCTAssertTrue(tiles.contains(2))
        tiles.remove(1)
        XCTAssertEqual(tiles.streams.map(\.id), [2])
        XCTAssertEqual(tiles.add(LiveStream(id: 3, name: "Three"), limit: 2), .added)
        tiles.removeAll()
        XCTAssertTrue(tiles.isEmpty)
    }
}
