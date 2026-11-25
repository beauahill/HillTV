import XCTest
@testable import HillTVCore

final class PlayerRoutingTests: XCTestCase {
    private func canOpen(_ string: String) -> Bool {
        MediaContainer.applePlayerCanOpen(URL(string: string)!)
    }

    func testFileTypesApplePlayerOpens() {
        XCTAssertTrue(canOpen("http://line.example.com/movie/user/pass/123.mp4"))
        XCTAssertTrue(canOpen("http://line.example.com/movie/user/pass/123.MP4"))
        XCTAssertTrue(canOpen("http://line.example.com/series/user/pass/55.m4v"))
        XCTAssertTrue(canOpen("http://line.example.com/movie/user/pass/9.mov"))
        XCTAssertTrue(canOpen("http://line.example.com/live/user/pass/7.m3u8"))
    }

    func testFileTypesThatNeedKSPlayer() {
        for type in ["mkv", "avi", "ts", "wmv", "flv", "webm", "mpg", "MKV"] {
            XCTAssertFalse(canOpen("http://line.example.com/movie/user/pass/123.\(type)"), type)
        }
    }

    func testNoFileTypeIsLeftToApplePlayer() {
        XCTAssertTrue(canOpen("http://line.example.com/movie/user/pass/123"))
    }

    func testMovieAndEpisodeURLsCarryTheirFileType() {
        let client = XtreamClient(account: XtreamAccount(
            serverURL: URL(string: "http://line.example.com:8080")!,
            username: "user",
            password: "pass"
        ))
        XCTAssertFalse(MediaContainer.applePlayerCanOpen(client.movieURL(streamID: 123, containerExtension: "mkv")))
        XCTAssertTrue(MediaContainer.applePlayerCanOpen(client.movieURL(streamID: 123, containerExtension: "mp4")))
        XCTAssertFalse(MediaContainer.applePlayerCanOpen(client.episodeURL(episodeID: "77", containerExtension: "avi")))
    }

    // MARK: ProgressTracker

    func testSavesEveryIntervalNotEveryReport() {
        var tracker = ProgressTracker()
        var saved: [Double] = []
        for tenth in 1...350 {
            if let report = tracker.report(position: Double(tenth) / 10, duration: 5400) {
                saved.append(report.position)
            }
        }
        XCTAssertEqual(saved, [0.1, 10.1, 20.1, 30.1])
        XCTAssertEqual(tracker.latest, .init(position: 35, duration: 5400))
    }

    func testIgnoresReportsFromBeforeTheResumePoint() {
        var tracker = ProgressTracker(resumeFrom: 1200)
        // The player starts at the beginning, then jumps.
        XCTAssertNil(tracker.report(position: 0, duration: 5400))
        XCTAssertNil(tracker.report(position: 0.4, duration: 5400))
        XCTAssertNil(tracker.report(position: 45, duration: 5400))
        XCTAssertNil(tracker.latest)
        // It lands a few seconds early, where the picture can start.
        XCTAssertEqual(tracker.report(position: 1194, duration: 5400)?.position, 1194)
        XCTAssertEqual(tracker.latest?.position, 1194)
    }

    func testGoingBackAfterResumingIsSaved() {
        var tracker = ProgressTracker(resumeFrom: 1200)
        XCTAssertNotNil(tracker.report(position: 1200, duration: 5400))
        // The viewer skips back to rewatch a scene.
        XCTAssertEqual(tracker.report(position: 300, duration: 5400)?.position, 300)
        XCTAssertEqual(tracker.latest?.position, 300)
    }

    func testNoResumePointSavesFromTheStart() {
        var tracker = ProgressTracker(resumeFrom: nil)
        XCTAssertNotNil(tracker.report(position: 2, duration: 5400))
        var short = ProgressTracker(resumeFrom: 12)
        XCTAssertNotNil(short.report(position: 1, duration: 5400))
    }

    func testBadNumbers() {
        var tracker = ProgressTracker()
        XCTAssertNil(tracker.report(position: .nan, duration: 5400))
        XCTAssertNil(tracker.report(position: -3, duration: 5400))
        XCTAssertNil(tracker.report(position: 0, duration: 5400))
        XCTAssertNil(tracker.latest)
        // A length of zero, or none, is saved as "not known".
        XCTAssertEqual(tracker.report(position: 40, duration: 0), .init(position: 40, duration: nil))
        XCTAssertEqual(tracker.report(position: 60, duration: .nan), .init(position: 60, duration: nil))
        XCTAssertEqual(tracker.report(position: 80, duration: .infinity), .init(position: 80, duration: nil))
    }
}
