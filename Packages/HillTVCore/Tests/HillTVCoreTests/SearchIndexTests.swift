import XCTest
@testable import HillTVCore

final class SearchIndexTests: XCTestCase {
    func testFoldRemovesCaseAccentsAndPunctuation() {
        XCTAssertEqual(SearchIndex<String>.fold("Ciné+ | FR: Amélie"), "cine fr amelie")
        XCTAssertEqual(SearchIndex<String>.fold("  BBC—One HD "), "bbc one hd")
        XCTAssertEqual(SearchIndex<String>.fold("|||"), "")
    }

    func testRanksPrefixThenWordStartThenContains() {
        let names = ["The Batman", "Batman Begins", "Lego Batman Movie", "Superbatman"]
        let index = SearchIndex(items: names) { $0 }
        XCTAssertEqual(index.search("batman", limit: 10), ["Batman Begins", "The Batman", "Lego Batman Movie", "Superbatman"])
    }

    func testAllWordsMustMatchInAnyOrder() {
        let index = SearchIndex(items: ["US: ESPN 2 HD", "UK: Sky Sports", "ESPN Deportes"]) { $0 }
        XCTAssertEqual(index.search("hd espn", limit: 10), ["US: ESPN 2 HD"])
        XCTAssertEqual(index.search("ÉSPN", limit: 10), ["ESPN Deportes", "US: ESPN 2 HD"])
    }

    func testEmptyQueryAndLimit() {
        let index = SearchIndex(items: (0..<50).map { "Channel \($0)" }) { $0 }
        XCTAssertEqual(index.search("   ", limit: 10), [])
        XCTAssertEqual(index.search("channel", limit: 5).count, 5)
        XCTAssertEqual(index.search("channel", limit: 0), [])
    }
}
