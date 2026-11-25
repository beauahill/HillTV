import XCTest
@testable import HillTVCore

final class CategoryNameTests: XCTestCase {
    private func parts(_ raw: String) -> [String] {
        let name = CategoryName(raw)
        return [name.title, name.tags.joined(separator: ",")]
    }

    /// The names Beau sent from his provider, as he typed them.
    func testNamesFromARealProvider() {
        XCTAssertEqual(parts("|NA| USA Sports"), ["USA Sports", "NA"])
        XCTAssertEqual(parts("SRS - HULU [EN}"), ["HULU", "EN"])
        XCTAssertEqual(parts("VOD - NEW ADDED [EN]"), ["NEW ADDED", "EN"])
        XCTAssertEqual(parts("VOD - NETFLIX [MULTISUB]"), ["NETFLIX", "MULTISUB"])
        XCTAssertEqual(parts("VOD - SPORT [EN]"), ["SPORT", "EN"])
        XCTAssertEqual(CategoryName("VOD - NETFLIX [MULTISUB]").detail, "MULTISUB")
        XCTAssertNil(CategoryName("Drama").detail)
        XCTAssertEqual(CategoryName("VOD - NETFLIX [MULTISUB]").line, "NETFLIX · MULTISUB")
        XCTAssertEqual(CategoryName("Drama").line, "Drama")
    }

    func testTwoCategoriesThatDifferOnlyByTagStayApart() {
        let english = CategoryName("VOD - NETFLIX [EN]")
        let multi = CategoryName("VOD - NETFLIX [MULTISUB]")
        XCTAssertEqual(english.title, multi.title)
        XCTAssertNotEqual(english.detail, multi.detail)
    }

    func testSeveralLabels() {
        XCTAssertEqual(parts("|NA| VOD - KIDS [EN] [4K]"), ["KIDS", "NA,EN,4K"])
        XCTAssertEqual(parts("|EU| UK Sports"), ["UK Sports", "EU"])
        XCTAssertEqual(parts("SRS - APPLE TV+ [MULTI-SUB]"), ["APPLE TV+", "MULTI-SUB"])
        XCTAssertEqual(CategoryName("|NA| VOD - KIDS [EN]").detail, "NA · EN")
    }

    func testNamesWithNothingToCut() {
        XCTAssertEqual(parts("Drama"), ["Drama", ""])
        XCTAssertEqual(parts("  Action & Adventure  "), ["Action & Adventure", ""])
        XCTAssertEqual(parts(""), ["", ""])
    }

    func testLookAlikesAreKept() {
        // The kind word needs its dash and spaces.
        XCTAssertEqual(parts("VODKA NIGHTS"), ["VODKA NIGHTS", ""])
        XCTAssertEqual(parts("VOD-CAST"), ["VOD-CAST", ""])
        XCTAssertEqual(parts("SRS"), ["SRS", ""])
        // A bracket in the middle is part of the name.
        XCTAssertEqual(parts("Best [EN] Dramas"), ["Best [EN] Dramas", ""])
        // Too long, or not letters, to be a label.
        XCTAssertEqual(parts("Films [the complete collection]"), ["Films [the complete collection]", ""])
        XCTAssertEqual(parts("Odd [!!]"), ["Odd [!!]", ""])
        // A lone bar is not a marker.
        XCTAssertEqual(parts("| Sports"), ["| Sports", ""])
        XCTAssertEqual(parts("|a very long marker| Sports"), ["|a very long marker| Sports", ""])
        // Round brackets weren't in the names seen, so they stay.
        XCTAssertEqual(parts("Movies (2024)"), ["Movies (2024)", ""])
    }

    func testNothingLeftShowsTheWholeName() {
        XCTAssertEqual(parts("[EN]"), ["[EN]", ""])
        XCTAssertEqual(parts("VOD - [EN]"), ["VOD - [EN]", ""])
        XCTAssertEqual(parts("|NA|"), ["|NA|", ""])
    }

    func testLetterCaseIsKept() {
        XCTAssertEqual(parts("vod - New Releases [en]"), ["New Releases", "en"])
    }

    func testCategoryExtension() {
        XCTAssertEqual(XtreamCategory(id: "7", name: "SRS - HULU [EN]").displayName.title, "HULU")
    }
}
