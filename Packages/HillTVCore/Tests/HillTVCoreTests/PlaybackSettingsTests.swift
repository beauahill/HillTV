import XCTest
@testable import HillTVCore

final class PlaybackSettingsTests: XCTestCase {
    private func decode(_ json: String) throws -> PlaybackSettings {
        try JSONDecoder().decode(PlaybackSettings.self, from: Data(json.utf8))
    }

    func testDefaultsMatchThePlayerBeforeSettingsExisted() {
        let settings = PlaybackSettings()
        XCTAssertEqual(settings.liveBuffer, .standard)
        XCTAssertEqual(settings.liveBuffer.seconds, 4)
        XCTAssertEqual(settings.liveDelay, .standard)
        XCTAssertFalse(settings.preservesLiveOffset)
        XCTAssertNil(settings.liveOffsetTarget(recommended: 30))
        XCTAssertEqual(settings.liveBufferSeconds(offsetTarget: nil), 4)
    }

    func testBufferSecondsAreInAscendingOrder() {
        let seconds = PlaybackSettings.LiveBuffer.allCases.map(\.seconds)
        XCTAssertEqual(seconds, [1, 2, 4, 10, 30])
    }

    func testRoundTrip() throws {
        var settings = PlaybackSettings()
        settings.liveBuffer = .thirtySeconds
        settings.liveDelay = .closest
        let data = try JSONEncoder().encode(settings)
        XCTAssertEqual(try JSONDecoder().decode(PlaybackSettings.self, from: data), settings)
    }

    func testMissingKeysFallBackToDefaults() throws {
        XCTAssertEqual(try decode("{}"), PlaybackSettings())

        let partial = try decode(#"{"liveDelay":"keepUp"}"#)
        XCTAssertEqual(partial.liveDelay, .keepUp)
        XCTAssertEqual(partial.liveBuffer, .standard)
    }

    func testAnUnknownValueResetsOnlyItsOwnSetting() throws {
        let settings = try decode(#"{"liveBuffer":"oneHour","liveDelay":"closest","somethingNew":true}"#)
        XCTAssertEqual(settings.liveBuffer, .standard)
        XCTAssertEqual(settings.liveDelay, .closest)

        let wrongType = try decode(#"{"liveBuffer":10,"liveDelay":"keepUp"}"#)
        XCTAssertEqual(wrongType.liveBuffer, .standard)
        XCTAssertEqual(wrongType.liveDelay, .keepUp)
    }

    func testOnlyClosestMovesTheLiveOffset() {
        var settings = PlaybackSettings()
        settings.liveDelay = .keepUp
        XCTAssertTrue(settings.preservesLiveOffset)
        XCTAssertNil(settings.liveOffsetTarget(recommended: 30))

        settings.liveDelay = .closest
        XCTAssertTrue(settings.preservesLiveOffset)
        XCTAssertEqual(settings.liveOffsetTarget(recommended: 30), 15)
    }

    func testClosestTargetIsHalfButNotUnderFourSecondsOrPastTheRecommendation() {
        var settings = PlaybackSettings()
        settings.liveDelay = .closest
        XCTAssertEqual(settings.liveOffsetTarget(recommended: 30), 15)
        XCTAssertEqual(settings.liveOffsetTarget(recommended: 9), 4.5)
        XCTAssertEqual(settings.liveOffsetTarget(recommended: 6), 4)
        // Already closer than four seconds: leave it where the stream wants it.
        XCTAssertEqual(settings.liveOffsetTarget(recommended: 3), 3)
    }

    func testAnUnknownRecommendationLeavesThePlayerAlone() {
        var settings = PlaybackSettings()
        settings.liveDelay = .closest
        XCTAssertNil(settings.liveOffsetTarget(recommended: nil))
        XCTAssertNil(settings.liveOffsetTarget(recommended: 0))
        XCTAssertNil(settings.liveOffsetTarget(recommended: -5))
        XCTAssertNil(settings.liveOffsetTarget(recommended: Double.nan))
        XCTAssertNil(settings.liveOffsetTarget(recommended: Double.infinity))
    }

    func testAChosenDistanceCapsTheLiveBuffer() {
        var settings = PlaybackSettings()
        settings.liveBuffer = .thirtySeconds
        XCTAssertEqual(settings.liveBufferSeconds(offsetTarget: 15), 15)
        XCTAssertEqual(settings.liveBufferSeconds(offsetTarget: 60), 30)
        XCTAssertEqual(settings.liveBufferSeconds(offsetTarget: nil), 30)
        XCTAssertEqual(settings.liveBufferSeconds(offsetTarget: 0), 30)

        settings.liveBuffer = .oneSecond
        XCTAssertEqual(settings.liveBufferSeconds(offsetTarget: 15), 1)
    }
}
