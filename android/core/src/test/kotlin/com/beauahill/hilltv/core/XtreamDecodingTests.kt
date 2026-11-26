package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.Instant

class XtreamDecodingTests {
    private fun <T : Any> list(json: String, decode: (kotlinx.serialization.json.JsonObject) -> T?): List<T> =
        parseJson(json).lossyList(decode)

    @Test
    fun testAuthInfoWithMixedTypes() {
        val json = """
        {"user_info":{"username":"beau","password":"x","auth":1,"status":"Active","exp_date":"1798761600",
          "is_trial":"0","active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts","rtmp"]},
         "server_info":{"url":"line.example.com","port":"8080","https_port":443,"server_protocol":"http","timezone":"America/Denver"}}
        """
        val info = XtreamAuthInfo.decode(parseJson(json))
        assertTrue(info.isAuthorized)
        assertEquals(2, info.user.maxConnections)
        assertEquals(listOf("m3u8", "ts", "rtmp"), info.user.allowedOutputFormats)
        assertEquals(Instant.ofEpochSecond(1_798_761_600), info.user.expiration)
        assertEquals("443", info.server?.httpsPort)
    }

    @Test
    fun testAuthInfoUnauthorized() {
        val info = XtreamAuthInfo.decode(parseJson("""{"user_info":{"auth":0}}"""))
        assertFalse(info.isAuthorized)
    }

    @Test
    fun testLiveStreamsSkipBadRowsAndCoerceTypes() {
        val json = """
        [
          {"num":1,"name":"KTVQ CBS","stream_type":"live","stream_id":101,"stream_icon":"http://logo/1.png",
           "epg_channel_id":"ktvq.us","category_id":"5","tv_archive":0,"tv_archive_duration":0},
          {"num":"2","name":"ESPN","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":5,"tv_archive":"1","tv_archive_duration":"3"},
          {"name":"No ID, should be dropped"},
          "garbage",
          {"num":3,"name":"Weird rating","stream_id":103.0,"category_id":"7"}
        ]
        """
        val streams = list(json, LiveStream::fromJson)
        assertEquals(listOf(101, 102, 103), streams.map { it.id })
        assertEquals(2, streams[1].number)
        assertNull(streams[1].iconURL)
        assertNull(streams[1].epgChannelID)
        assertEquals("5", streams[1].categoryID)
        assertTrue(streams[1].hasArchive)
        assertEquals(3, streams[1].archiveDays)

        // The client reads lists off a stream instead of a tree. Same rows either way.
        assertEquals(streams, XtreamClient.decodeLossyList(json.byteInputStream(), LiveStream::fromJson))
    }

    @Test
    fun testNonArrayPayloadDecodesAsEmpty() {
        for (payload in listOf("{}", "null", "false", "[]")) {
            assertTrue("payload $payload", list(payload, XtreamCategory::fromJson).isEmpty())
            assertTrue("stream $payload", XtreamClient.decodeLossyList(payload.byteInputStream(), XtreamCategory::fromJson).isEmpty())
        }
    }

    @Test
    fun testCategories() {
        val json = """[{"category_id":"1","category_name":"US | News","parent_id":0},{"category_id":22,"category_name":"Sports"}]"""
        val cats = list(json, XtreamCategory::fromJson)
        assertEquals(listOf("1", "22"), cats.map { it.id })
        assertEquals("0", cats[0].parentID)
    }

    @Test
    fun testVODStream() {
        val json = """[{"num":1,"name":"Tremors","stream_id":"5001","stream_icon":"http://img/p.jpg","rating":"7.1","category_id":"9","container_extension":"MKV","added":"1700000000"}]"""
        val movie = list(json, VODStream::fromJson).first()
        assertEquals(5001, movie.id)
        assertEquals("mkv", movie.containerExtension)
        assertEquals(7.1, movie.rating ?: 0.0, 0.001)
    }

    @Test
    fun testVODInfoFullPayload() {
        val json = """
        {"info":{"name":"Tremors","movie_image":"http://img.example.com/p.jpg","backdrop_path":["http://img.example.com/b.jpg"],
                 "description":"Worms.","genre":"Horror / Comedy","director":"Ron Underwood","actors":"Kevin Bacon",
                 "release_date":"1990-01-19","duration":"01:36:00","rating":"7.1"},
         "movie_data":{"stream_id":"5001","name":"Tremors (1990)","container_extension":"MKV"}}
        """
        val info = VODInfo.decode(parseJson(json))
        assertEquals("Tremors", info.name)
        assertEquals("Worms.", info.plot)
        assertEquals("Kevin Bacon", info.cast)
        assertEquals("1990", info.year)
        assertEquals(5760, info.durationSeconds)
        assertEquals("img.example.com", info.backdropURL?.let { URI(it).host })
        assertEquals("mkv", info.containerExtension)
        assertEquals(7.1, info.rating ?: 0.0, 0.001)
    }

    @Test
    fun testVODInfoEmptyInfoArrayAndOddTypes() {
        val empty = """{"info":[],"movie_data":{"stream_id":5002,"name":"Bare","container_extension":"mp4"}}"""
        val bare = VODInfo.decode(parseJson(empty))
        assertEquals("Bare", bare.name)
        assertNull(bare.plot)

        val odd = """{"info":{"plot":"","backdrop_path":"","duration_secs":"6840","rating":0,"releasedate":"n/a"},"movie_data":[]}"""
        val info = VODInfo.decode(parseJson(odd))
        assertNull(info.plot)
        assertNull(info.backdropURL)
        assertEquals(6840, info.durationSeconds)
        assertNull(info.rating)
        assertNull(info.year)
    }

    @Test
    fun testSeriesInfoDictionaryEpisodes() {
        val json = """
        {"seasons":[],"info":{"name":"Yellowstone","plot":"Ranch.","cover":"http://img/c.jpg"},
         "episodes":{"2":[{"id":"9002","episode_num":"1","title":"S2E1","container_extension":"mp4","season":2,"info":{"duration_secs":"3600"}}],
                     "1":[{"id":"9001","episode_num":2,"title":"S1E2","container_extension":"mkv","season":"1","info":[]},
                          {"id":"9000","episode_num":1,"title":"S1E1","container_extension":"mkv","season":1}]}}
        """
        val info = SeriesInfo.decode(parseJson(json))
        assertEquals("Yellowstone", info.name)
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(listOf("9000", "9001"), info.seasons[0].episodes.map { it.id })
        assertEquals(3600, info.seasons[1].episodes.first().durationSeconds)
    }

    @Test
    fun testSeriesInfoArrayEpisodes() {
        val json = """
        {"info":{"name":"Show"},"episodes":[[{"id":"1","episode_num":1,"season":1,"container_extension":"mp4"}],
                                            [{"id":"2","episode_num":1,"season":2,"container_extension":"mp4"}]]}
        """
        val info = SeriesInfo.decode(parseJson(json))
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
    }

    @Test
    fun testShortEPGDecodesBase64() {
        // "Evening News" / "Local headlines"
        val json = """
        {"epg_listings":[{"id":"1","epg_id":"1","title":"RXZlbmluZyBOZXdz","lang":"en","start":"2026-11-20 23:00:00",
          "end":"2026-11-20 23:30:00","description":"TG9jYWwgaGVhZGxpbmVz","channel_id":"ktvq.us",
          "start_timestamp":"1763679600","stop_timestamp":"1763681400"}]}
        """
        val entry = EPGEntry.decodeShortEPG(parseJson(json)).first()
        assertEquals("Evening News", entry.title)
        assertEquals("Local headlines", entry.summary)
        assertEquals(1800.0, seconds(entry.start, entry.end), 0.0)
        assertEquals(0.5, entry.progress(entry.start.plusSeconds(900)), 0.001)
    }

    @Test
    fun testLiveStreamRoundTripsForFavorites() {
        val original = LiveStream(
            id = 7, number = 3, name = "Fox Sports", iconURL = "http://logo/x.png",
            epgChannelID = "fs1.us", categoryID = "4", hasArchive = true, archiveDays = 2,
        )
        val decoded = LiveStream.fromJson(parseJson(original.toJson().toString()) as kotlinx.serialization.json.JsonObject)
        assertEquals(original, decoded)
    }

    // Android-only: text that is not base64, or not UTF-8 underneath, stays as sent.
    @Test
    fun testPlainTextTitlesAreKeptWhenTheyAreNotBase64() {
        assertNull(EPGEntry.decodeBase64("Evening News"))
        assertNull(EPGEntry.decodeBase64("News"))
        assertNull(EPGEntry.decodeBase64(""))
        assertEquals("Evening News", EPGEntry.decodeBase64("RXZlbmlu\nZyBOZXdz"))
        assertEquals("Amélie", EPGEntry.decodeBase64("QW3DqWxpZQ=="))
    }
}
