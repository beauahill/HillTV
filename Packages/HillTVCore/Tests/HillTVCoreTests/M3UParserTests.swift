import XCTest
@testable import HillTVCore

final class M3UParserTests: XCTestCase {
    func testParsesAttributesAndNames() {
        let playlist = """
        #EXTM3U x-tvg-url="http://epg.example/xmltv.xml"
        #EXTINF:-1 tvg-id="ktvq.us" tvg-name="KTVQ" tvg-logo="http://logo/ktvq.png" group-title="US, Locals",KTVQ CBS Billings
        http://line.example.com:8080/beau/pass/101.ts

        #EXTINF:-1 tvg-id="" group-title="Sports",ESPN, HD
        #EXTVLCOPT:http-user-agent=HillTV
        http://line.example.com:8080/beau/pass/102.ts
        #EXTINF:-1,No attributes
        #EXTGRP:Misc
        https://example.com/stream.m3u8
        """
        let entries = M3UParser.parse(playlist)
        XCTAssertEqual(entries.count, 3)

        XCTAssertEqual(entries[0].name, "KTVQ CBS Billings")
        XCTAssertEqual(entries[0].tvgID, "ktvq.us")
        XCTAssertEqual(entries[0].group, "US, Locals")
        XCTAssertEqual(entries[0].logoURL?.absoluteString, "http://logo/ktvq.png")

        XCTAssertEqual(entries[1].name, "ESPN, HD")
        XCTAssertNil(entries[1].tvgID)
        XCTAssertEqual(entries[1].group, "Sports")

        XCTAssertEqual(entries[2].name, "No attributes")
        XCTAssertEqual(entries[2].group, "Misc")
    }
}
