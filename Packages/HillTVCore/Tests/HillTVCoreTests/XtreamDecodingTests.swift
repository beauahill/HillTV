import XCTest
@testable import HillTVCore

final class XtreamDecodingTests: XCTestCase {
    private let decoder = JSONDecoder()

    func testAuthInfoWithMixedTypes() throws {
        let json = """
        {"user_info":{"username":"beau","password":"x","auth":1,"status":"Active","exp_date":"1798761600",
          "is_trial":"0","active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts","rtmp"]},
         "server_info":{"url":"line.example.com","port":"8080","https_port":443,"server_protocol":"http","timezone":"America/Denver"}}
        """
        let info = try decoder.decode(XtreamAuthInfo.self, from: Data(json.utf8))
        XCTAssertTrue(info.isAuthorized)
        XCTAssertEqual(info.user.maxConnections, 2)
        XCTAssertEqual(info.user.allowedOutputFormats, ["m3u8", "ts", "rtmp"])
        XCTAssertEqual(info.user.expiration, Date(timeIntervalSince1970: 1_798_761_600))
        XCTAssertEqual(info.server?.httpsPort, "443")
    }

    func testAuthInfoUnauthorized() throws {
        let json = #"{"user_info":{"auth":0}}"#
        let info = try decoder.decode(XtreamAuthInfo.self, from: Data(json.utf8))
        XCTAssertFalse(info.isAuthorized)
    }

    func testLiveStreamsSkipBadRowsAndCoerceTypes() throws {
        let json = """
        [
          {"num":1,"name":"KTVQ CBS","stream_type":"live","stream_id":101,"stream_icon":"http://logo/1.png",
           "epg_channel_id":"ktvq.us","category_id":"5","tv_archive":0,"tv_archive_duration":0},
          {"num":"2","name":"ESPN","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":5,"tv_archive":"1","tv_archive_duration":"3"},
          {"name":"No ID, should be dropped"},
          "garbage",
          {"num":3,"name":"Weird rating","stream_id":103.0,"category_id":"7"}
        ]
        """
        let streams = try decoder.decode(LossyArray<LiveStream>.self, from: Data(json.utf8)).elements
        XCTAssertEqual(streams.map(\.id), [101, 102, 103])
        XCTAssertEqual(streams[1].number, 2)
        XCTAssertNil(streams[1].iconURL)
        XCTAssertNil(streams[1].epgChannelID)
        XCTAssertEqual(streams[1].categoryID, "5")
        XCTAssertTrue(streams[1].hasArchive)
        XCTAssertEqual(streams[1].archiveDays, 3)
    }

    func testNonArrayPayloadDecodesAsEmpty() throws {
        for payload in ["{}", "null", "false", "[]"] {
            let result = try decoder.decode(LossyArray<XtreamCategory>.self, from: Data(payload.utf8))
            XCTAssertTrue(result.elements.isEmpty, "payload \(payload)")
        }
    }

    func testCategories() throws {
        let json = #"[{"category_id":"1","category_name":"US | News","parent_id":0},{"category_id":22,"category_name":"Sports"}]"#
        let cats = try decoder.decode(LossyArray<XtreamCategory>.self, from: Data(json.utf8)).elements
        XCTAssertEqual(cats.map(\.id), ["1", "22"])
        XCTAssertEqual(cats[0].parentID, "0")
    }

    func testVODStream() throws {
        let json = #"[{"num":1,"name":"Tremors","stream_id":"5001","stream_icon":"http://img/p.jpg","rating":"7.1","category_id":"9","container_extension":"MKV","added":"1700000000"}]"#
        let movie = try XCTUnwrap(decoder.decode(LossyArray<VODStream>.self, from: Data(json.utf8)).elements.first)
        XCTAssertEqual(movie.id, 5001)
        XCTAssertEqual(movie.containerExtension, "mkv")
        XCTAssertEqual(movie.rating ?? 0, 7.1, accuracy: 0.001)
    }

    func testVODInfoFullPayload() throws {
        let json = """
        {"info":{"name":"Tremors","movie_image":"http://img.example.com/p.jpg","backdrop_path":["http://img.example.com/b.jpg"],
                 "description":"Worms.","genre":"Horror / Comedy","director":"Ron Underwood","actors":"Kevin Bacon",
                 "release_date":"1990-01-19","duration":"01:36:00","rating":"7.1"},
         "movie_data":{"stream_id":"5001","name":"Tremors (1990)","container_extension":"MKV"}}
        """
        let info = try decoder.decode(VODInfo.self, from: Data(json.utf8))
        XCTAssertEqual(info.name, "Tremors")
        XCTAssertEqual(info.plot, "Worms.")
        XCTAssertEqual(info.cast, "Kevin Bacon")
        XCTAssertEqual(info.year, "1990")
        XCTAssertEqual(info.durationSeconds, 5760)
        XCTAssertEqual(info.backdropURL?.host, "img.example.com")
        XCTAssertEqual(info.containerExtension, "mkv")
        XCTAssertEqual(info.rating ?? 0, 7.1, accuracy: 0.001)
    }

    func testVODInfoEmptyInfoArrayAndOddTypes() throws {
        let empty = #"{"info":[],"movie_data":{"stream_id":5002,"name":"Bare","container_extension":"mp4"}}"#
        let bare = try decoder.decode(VODInfo.self, from: Data(empty.utf8))
        XCTAssertEqual(bare.name, "Bare")
        XCTAssertNil(bare.plot)

        let odd = #"{"info":{"plot":"","backdrop_path":"","duration_secs":"6840","rating":0,"releasedate":"n/a"},"movie_data":[]}"#
        let info = try decoder.decode(VODInfo.self, from: Data(odd.utf8))
        XCTAssertNil(info.plot)
        XCTAssertNil(info.backdropURL)
        XCTAssertEqual(info.durationSeconds, 6840)
        XCTAssertNil(info.rating)
        XCTAssertNil(info.year)
    }

    func testSeriesInfoDictionaryEpisodes() throws {
        let json = """
        {"seasons":[],"info":{"name":"Yellowstone","plot":"Ranch.","cover":"http://img/c.jpg"},
         "episodes":{"2":[{"id":"9002","episode_num":"1","title":"S2E1","container_extension":"mp4","season":2,"info":{"duration_secs":"3600"}}],
                     "1":[{"id":"9001","episode_num":2,"title":"S1E2","container_extension":"mkv","season":"1","info":[]},
                          {"id":"9000","episode_num":1,"title":"S1E1","container_extension":"mkv","season":1}]}}
        """
        let info = try decoder.decode(SeriesInfo.self, from: Data(json.utf8))
        XCTAssertEqual(info.name, "Yellowstone")
        XCTAssertEqual(info.seasons.map(\.number), [1, 2])
        XCTAssertEqual(info.seasons[0].episodes.map(\.id), ["9000", "9001"])
        XCTAssertEqual(info.seasons[1].episodes.first?.durationSeconds, 3600)
    }

    func testSeriesInfoArrayEpisodes() throws {
        let json = """
        {"info":{"name":"Show"},"episodes":[[{"id":"1","episode_num":1,"season":1,"container_extension":"mp4"}],
                                            [{"id":"2","episode_num":1,"season":2,"container_extension":"mp4"}]]}
        """
        let info = try decoder.decode(SeriesInfo.self, from: Data(json.utf8))
        XCTAssertEqual(info.seasons.map(\.number), [1, 2])
    }

    func testShortEPGDecodesBase64() throws {
        // "Evening News" / "Local headlines"
        let json = """
        {"epg_listings":[{"id":"1","epg_id":"1","title":"RXZlbmluZyBOZXdz","lang":"en","start":"2026-11-20 23:00:00",
          "end":"2026-11-20 23:30:00","description":"TG9jYWwgaGVhZGxpbmVz","channel_id":"ktvq.us",
          "start_timestamp":"1763679600","stop_timestamp":"1763681400"}]}
        """
        let response = try decoder.decode(ShortEPGResponse.self, from: Data(json.utf8))
        let entry = try XCTUnwrap(response.listings.first)
        XCTAssertEqual(entry.title, "Evening News")
        XCTAssertEqual(entry.summary, "Local headlines")
        XCTAssertEqual(entry.end.timeIntervalSince(entry.start), 1800)
        XCTAssertEqual(entry.progress(at: entry.start.addingTimeInterval(900)), 0.5, accuracy: 0.001)
    }

    func testLiveStreamRoundTripsForFavorites() throws {
        let original = LiveStream(id: 7, number: 3, name: "Fox Sports", iconURL: URL(string: "http://logo/x.png"),
                                  epgChannelID: "fs1.us", categoryID: "4", hasArchive: true, archiveDays: 2)
        let data = try JSONEncoder().encode(original)
        let decoded = try decoder.decode(LiveStream.self, from: data)
        XCTAssertEqual(decoded, original)
    }
}
