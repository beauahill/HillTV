import XCTest
@testable import HillTVCore

final class ChannelNameTests: XCTestCase {
    private func parts(_ raw: String) -> [String] {
        let name = ChannelName(raw)
        return [name.title, name.region ?? "-", name.quality.joined(separator: ",")]
    }

    func testRegionAndQualityAreSplitOff() {
        XCTAssertEqual(parts("US | CBS 13 CHEYENNE HD"), ["CBS 13 CHEYENNE", "US", "HD"])
        XCTAssertEqual(parts("US: ESPN FHD"), ["ESPN", "US", "FHD"])
        XCTAssertEqual(parts("UK ▎ BBC One"), ["BBC One", "UK", ""])
        XCTAssertEqual(parts("US| ABC"), ["ABC", "US", ""])
        XCTAssertEqual(parts("US - FOX NEWS"), ["FOX NEWS", "US", ""])
        XCTAssertEqual(parts("USA | Syfy"), ["Syfy", "USA", ""])
        XCTAssertEqual(parts("LATINO | ESPN 2"), ["ESPN 2", "LATINO", ""])
        XCTAssertEqual(parts("CA FR | TVA Sports"), ["TVA Sports", "CA FR", ""])
        XCTAssertEqual(parts("  de: ZDF  "), ["ZDF", "de", ""])
        XCTAssertEqual(ChannelName("US | CBS 13 CHEYENNE HD").detail, "US · HD")
        XCTAssertNil(ChannelName("HGTV").detail)
    }

    func testBracketedRegions() {
        XCTAssertEqual(parts("[US] CBS"), ["CBS", "US", ""])
        XCTAssertEqual(parts("(UK) Sky Sports Main Event UHD"), ["Sky Sports Main Event", "UK", "UHD"])
        XCTAssertEqual(parts("|FR| TF1 4K"), ["TF1", "FR", "4K"])
        XCTAssertEqual(parts("[US] - NBC"), ["NBC", "US", ""])
        // The form Beau's provider uses, "NA" for North America.
        XCTAssertEqual(parts("|NA| USA Sports"), ["USA Sports", "NA", ""])
        XCTAssertEqual(parts("|NA| ESPN HD"), ["ESPN", "NA", "HD"])
        // Not a region, so the bracket is part of the name.
        XCTAssertEqual(parts("(WNYW) FOX 5 New York"), ["(WNYW) FOX 5 New York", "-", ""])
    }

    func testNamesThatLookLikePrefixesAreKept() {
        // A network name before a colon is not a country.
        XCTAssertEqual(parts("ABC: World News"), ["ABC: World News", "-", ""])
        XCTAssertEqual(parts("NFL | Sunday Ticket 01"), ["NFL | Sunday Ticket 01", "-", ""])
        // "AL" is a country code, but not when a hyphen joins it to a word.
        XCTAssertEqual(parts("AL-JAZEERA"), ["AL-JAZEERA", "-", ""])
        XCTAssertEqual(parts("AL JAZEERA ENGLISH HD"), ["AL JAZEERA ENGLISH", "-", "HD"])
        // No separator, so nothing says "US" is a label.
        XCTAssertEqual(parts("US Open Channel"), ["US Open Channel", "-", ""])
        // The separator is too far in to be a prefix.
        XCTAssertEqual(parts("Sky Sports Football | UK"), ["Sky Sports Football | UK", "-", ""])
    }

    func testQualityOnlyFromTheEnd() {
        XCTAssertEqual(parts("ESPN HD FHD 60FPS"), ["ESPN", "-", "HD,FHD,60FPS"])
        XCTAssertEqual(parts("ESPN - HD"), ["ESPN", "-", "HD"])
        XCTAssertEqual(parts("CNN [FHD]"), ["CNN", "-", "FHD"])
        XCTAssertEqual(parts("TNT (H.265)"), ["TNT", "-", "H265"])
        XCTAssertEqual(parts("Channel 4 hd"), ["Channel 4", "-", "HD"])
        XCTAssertEqual(parts("HBO HD East"), ["HBO HD East", "-", ""])
        XCTAssertEqual(parts("US | CBS 13 CHEYENNE"), ["CBS 13 CHEYENNE", "US", ""])
    }

    func testSuperscriptLabels() {
        XCTAssertEqual(parts("UK | BBC ONE ᴴᴰ"), ["BBC ONE", "UK", "HD"])
        XCTAssertEqual(parts("ESPN ᶠᴴᴰ"), ["ESPN", "-", "FHD"])
        XCTAssertEqual(parts("ESPN ᴿᴬᵂ"), ["ESPN", "-", "RAW"])
    }

    func testLabelsThatAreAlsoWords() {
        XCTAssertEqual(parts("US | WWE RAW"), ["WWE RAW", "US", ""])
        XCTAssertEqual(parts("BALLY SPORTS SD"), ["BALLY SPORTS SD", "-", ""])
        XCTAssertEqual(parts("CNN [SD]"), ["CNN", "-", "SD"])
        XCTAssertEqual(parts("ESPN HD (BACKUP)"), ["ESPN", "-", "HD,BACKUP"])
    }

    func testDividerRowsAndNamesWithNothingLeft() {
        XCTAssertEqual(parts("##### NEWS #####"), ["NEWS", "-", ""])
        XCTAssertEqual(parts("--- US | SPORTS ---"), ["SPORTS", "US", ""])
        XCTAssertEqual(parts("#1 News"), ["#1 News", "-", ""])
        // Stripping would leave nothing, so the name is shown whole.
        XCTAssertEqual(parts("HD"), ["HD", "-", ""])
        XCTAssertEqual(parts("US | HD"), ["US | HD", "-", ""])
        XCTAssertEqual(parts("US |"), ["US", "-", ""])
        XCTAssertEqual(parts("#####"), ["#####", "-", ""])
        XCTAssertEqual(parts(""), ["", "-", ""])
    }

    func testLetterCaseAndAccentsAreKept() {
        XCTAssertEqual(parts("FR | Ciné+ Émotion HD"), ["Ciné+ Émotion", "FR", "HD"])
        XCTAssertEqual(parts("ES: La 1"), ["La 1", "ES", ""])
    }
}
