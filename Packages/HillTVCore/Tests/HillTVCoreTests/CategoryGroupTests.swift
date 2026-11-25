import XCTest
@testable import HillTVCore

final class CategoryGroupTests: XCTestCase {
    private func assertGroup(_ name: String, _ expected: CategoryGroup, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(CategoryGroup.classify(name), expected, "\"\(name)\"", file: file, line: line)
    }

    func testTopicsFromTypicalProviderNames() {
        assertGroup("US | SPORTS HD", .sports)
        assertGroup("NFL PACKAGE", .sports)
        assertGroup("PPV EVENTS", .sports)
        assertGroup("NBA LEAGUE PASS", .sports)
        assertGroup("VIP | BEIN SPORTS", .sports)
        assertGroup("World Cup 2026", .sports)
        assertGroup("UK | NEWS", .news)
        assertGroup("WEATHER & BUSINESS", .news)
        assertGroup("Christmas Movies", .movies)
        assertGroup("MOVIES IN 4K", .movies)
        assertGroup("US | ENTERTAINMENT", .shows)
        assertGroup("Documentaries", .shows)
        assertGroup("CA | LOCALS", .shows)
        assertGroup("Disney+ Kids", .kids)
        assertGroup("MTV & MUSIC", .music)
        assertGroup("Música", .music)
    }

    func testANameMatchingTwoTopicsTakesTheEarlierOne() {
        assertGroup("KIDS MOVIES", .kids)
        assertGroup("SPORTS NEWS", .sports)
        assertGroup("24/7 MOVIES", .movies)
    }

    func testForeignMarkersWinOverTopics() {
        assertGroup("FR | SPORT", .world)
        assertGroup("LATINO NEWS", .world)
        assertGroup("ARABIC | KIDS", .world)
        assertGroup("Canada French", .world)
        assertGroup("DE: KINDER", .world)
    }

    /// A live category as Beau's provider names them: a continent between bars.
    func testBarMarkerFromARealProvider() {
        assertGroup("|NA| USA Sports", .sports)
        assertGroup("|NA| USA News", .news)
        assertGroup("|NA| USA Kids", .kids)
    }

    func testEnglishMarkersDoNotCountAsForeign() {
        assertGroup("USA", .shows)
        assertGroup("UK HD", .shows)
        assertGroup("24/7 CHANNELS", .shows)
    }

    func testCountryCodesOnlyCountAsTheFirstWord() {
        // "it" and "es" appear as ordinary words here, not as a prefix.
        assertGroup("WATCH IT LIVE SPORTS", .sports)
        assertGroup("NO SIGNAL TEST", .other)
    }

    func testUnrecognizedAndEmptyNamesGoToOther() {
        assertGroup("ADULT", .other)
        assertGroup("4K UHD", .other)
        assertGroup("Religious", .other)
        assertGroup("|||", .other)
        assertGroup("", .other)
    }

    func testGroupingKeepsEveryCategoryInPillAndProviderOrder() {
        let names = ["UK | NEWS", "US | SPORTS", "Mystery Box", "NFL", "FR | CINEMA", "US | NEWS"]
        let categories = names.enumerated().map { XtreamCategory(id: String($0.offset), name: $0.element) }
        let grouped = CategoryGroup.grouping(categories)

        XCTAssertEqual(grouped.map(\.group), [.sports, .news, .world, .other])
        XCTAssertEqual(grouped[0].categories.map(\.name), ["US | SPORTS", "NFL"])
        XCTAssertEqual(grouped[1].categories.map(\.name), ["UK | NEWS", "US | NEWS"])
        XCTAssertEqual(grouped.reduce(0) { $0 + $1.categories.count }, categories.count)
    }

    func testGroupingOfNothingIsEmpty() {
        XCTAssertEqual(CategoryGroup.grouping([]), [])
    }
}

final class CategoryVisibilityTests: XCTestCase {
    private let categories = ["1", "2", "3", "4"].map { XtreamCategory(id: $0, name: "Category \($0)") }

    func testNothingIsHiddenAtFirst() {
        let visibility = CategoryVisibility()
        XCTAssertFalse(visibility.isHidden("1", in: .live))
        XCTAssertEqual(visibility.hiddenCount(in: .live), 0)
        XCTAssertEqual(visibility.visible(categories, in: .live), categories)
    }

    func testHidingIsPerCatalog() {
        var visibility = CategoryVisibility()
        visibility.setHidden(true, ids: ["2", "3"], in: .live)

        XCTAssertEqual(visibility.visible(categories, in: .live).map(\.id), ["1", "4"])
        XCTAssertEqual(visibility.visible(categories, in: .movies).map(\.id), ["1", "2", "3", "4"])
        XCTAssertEqual(visibility.hiddenCount(in: .live), 2)
        XCTAssertEqual(visibility.hiddenCount(in: .series), 0)
    }

    func testToggleAndBulkShow() {
        var visibility = CategoryVisibility()
        visibility.toggle("1", in: .movies)
        XCTAssertTrue(visibility.isHidden("1", in: .movies))
        visibility.toggle("1", in: .movies)
        XCTAssertFalse(visibility.isHidden("1", in: .movies))

        visibility.setHidden(true, ids: categories.map(\.id), in: .series)
        visibility.setHidden(false, ids: ["2"], in: .series)
        XCTAssertEqual(visibility.visible(categories, in: .series).map(\.id), ["2"])
        visibility.showAll(in: .series)
        XCTAssertEqual(visibility.hiddenCount(in: .series), 0)
    }

    func testAnEmptiedSetEqualsAFreshOne() {
        var visibility = CategoryVisibility()
        visibility.setHidden(true, ids: ["9"], in: .live)
        visibility.setHidden(false, ids: ["9"], in: .live)
        XCTAssertEqual(visibility, CategoryVisibility())
    }

    func testSurvivesEncodingAndDecoding() throws {
        var visibility = CategoryVisibility()
        visibility.setHidden(true, ids: ["2", "3"], in: .live)
        visibility.setHidden(true, ids: ["7"], in: .series)

        let data = try JSONEncoder().encode(visibility)
        let decoded = try JSONDecoder().decode(CategoryVisibility.self, from: data)
        XCTAssertEqual(decoded, visibility)
        XCTAssertTrue(decoded.isHidden("7", in: .series))
        XCTAssertFalse(decoded.isHidden("7", in: .live))
    }
}
