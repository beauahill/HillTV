package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class XtreamClientURLTests {
    @Test
    fun testNormalizeServer() {
        assertEquals("http://line.example.com:8080", XtreamAccount.normalizeServer("line.example.com:8080"))
        assertEquals("http://line.example.com:8080", XtreamAccount.normalizeServer(" http://line.example.com:8080/ "))
        assertEquals(
            "https://host.tv/panel",
            XtreamAccount.normalizeServer("https://host.tv/panel/player_api.php?username=a&password=b"),
        )
        assertEquals("HTTP://host.tv", XtreamAccount.normalizeServer("HTTP://host.tv/get.php"))
        assertNull(XtreamAccount.normalizeServer(""))
        assertNull(XtreamAccount.normalizeServer("   "))
    }

    @Test
    fun testAPIURLEncodesPlusAndAmpersand() {
        val client = makeClient(password = "a+b&c d")
        val url = client.apiURL("get_live_streams", listOf("category_id" to "5"))
        val query = URI(url).rawQuery
        assertTrue(query, query.contains("password=a%2Bb%26c%20d"))
        assertTrue(query.contains("action=get_live_streams"))
        assertTrue(query.contains("category_id=5"))
        assertEquals("/player_api.php", URI(url).path)
    }

    @Test
    fun testStreamURLs() {
        val client = makeClient(password = "p/ss?#")
        assertEquals(
            "http://line.example.com:8080/live/beau/p%2Fss%3F%23/101.m3u8",
            client.liveURL(101, LiveFormat.HLS),
        )
        assertTrue(client.liveURL(101, LiveFormat.MPEGTS).endsWith(".ts"))
    }

    @Test
    fun testBasePathIsKept() {
        val account = XtreamAccount(XtreamAccount.normalizeServer("https://host.tv/panel/")!!, "u", "p")
        val client = XtreamClient(account)
        assertEquals("/panel/player_api.php", URI(client.apiURL(null)).path)
        assertEquals("https://host.tv/panel/live/u/p/1.m3u8", client.liveURL(1, LiveFormat.HLS))
        assertEquals("/panel/xmltv.php", URI(client.xmltvURL).path)
    }

    // Android-only: the hand-written encoders stand in for Foundation's.
    @Test
    fun testEncodersHandleUnicodeAndReservedCharacters() {
        assertEquals("caf%C3%A9%20%231", XtreamClient.encodePathSegment("café #1"))
        assertEquals("a%3Bb:c@d", XtreamClient.encodePathSegment("a;b:c@d"))
        assertEquals("k=a%3Db%26c%2Bd%20e%25", XtreamClient.encodeQuery(listOf("k" to "a=b&c+d e%")))
        assertEquals("user=me@example.com", XtreamClient.encodeQuery(listOf("user" to "me@example.com")))
    }

    /**
     * The expected strings are what Foundation printed for the same input
     * (URLComponents.queryItems with "+" fixed, and addingPercentEncoding
     * with urlPathAllowed minus "/?#;"), run with the swift:6.2 image on
     * Linux, Nov 2026. If either encoder changes, both apps must still send
     * the same thing.
     */
    @Test
    fun testEncodersMatchFoundationForEveryPunctuationCharacter() {
        val sample = " !\"#${'$'}%&'()*+,-./:;<=>?@[\\]^_`{|}~é€"
        assertEquals(
            "p=%20!%22%23${'$'}%25%26'()*%2B,-./:;%3C%3D%3E?@%5B%5C%5D%5E_%60%7B%7C%7D~%C3%A9%E2%82%AC",
            XtreamClient.encodeQuery(listOf("p" to sample)),
        )
        assertEquals(
            "%20!%22%23${'$'}%25&'()*+,-.%2F:%3B%3C=%3E%3F@%5B%5C%5D%5E_%60%7B%7C%7D~%C3%A9%E2%82%AC",
            XtreamClient.encodePathSegment(sample),
        )
    }

    @Test
    fun testNormalizeServerRejectsWhatIsNotAnAddress() {
        assertNull(XtreamAccount.normalizeServer("http://"))
        assertNull(XtreamAccount.normalizeServer("my server"))
        assertEquals("http://192.168.1.5:8080", XtreamAccount.normalizeServer("192.168.1.5:8080/xmltv.php"))
    }

    private fun makeClient(password: String): XtreamClient =
        XtreamClient(XtreamAccount("http://line.example.com:8080", "beau", password))
}
