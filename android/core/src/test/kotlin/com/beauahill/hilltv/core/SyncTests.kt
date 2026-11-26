package com.beauahill.hilltv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SyncSettingsTests {
    private fun sample(): SyncSettings {
        val hidden = CategoryVisibility()
            .setHidden(true, listOf("1", "2"), CatalogKind.LIVE)
            .setHidden(true, listOf("9"), CatalogKind.MOVIES)
        val order = ChannelOrder().move(3, 0, listOf(1, 2, 3), "12")
        return SyncSettings(
            account = SyncSettings.Account("http://line.example.com:8080", "viewer"),
            hiddenCategories = hidden,
            favorites = listOf(LiveStream(id = 7, name = "US | CBS"), LiveStream(id = 8, name = "ESPN")),
            channelOrder = order,
        )
    }

    private fun readError(bytes: ByteArray): SyncSettings.ReadError? = try {
        SyncSettings.read(bytes)
        null
    } catch (error: SyncSettings.ReadError) {
        error
    }

    @Test
    fun testRoundTrip() {
        val file = sample()
        val read = SyncSettings.read(file.encoded())
        assertEquals(file, read)
        assertTrue(read.hiddenCategories.isHidden("2", CatalogKind.LIVE))
        assertEquals(listOf(7, 8), read.favorites.map { it.id })
    }

    @Test
    fun testNeverHoldsThePassword() {
        val text = sample().encoded().toString(Charsets.UTF_8)
        assertFalse(text.lowercase().contains("password"))
        assertTrue(text.contains("\"format\": \"hilltv.settings\""))
    }

    @Test
    fun testRefusesOtherFiles() {
        assertEquals(SyncSettings.ReadError.NotASettingsFile, readError("{}".toByteArray()))
        assertEquals(SyncSettings.ReadError.NotASettingsFile, readError("not json".toByteArray()))
        assertEquals(
            SyncSettings.ReadError.NotASettingsFile,
            readError("""{"format":"other","account":{"server":"a","username":"b"}}""".toByteArray()),
        )
        assertEquals(SyncSettings.ReadError.TooLarge, readError(ByteArray(SyncSettings.MAXIMUM_BYTES + 1) { 32 }))
    }

    @Test
    fun testNewerVersionIsRefusedWithAReason() {
        val json = """{"format":"hilltv.settings","version":2,"account":{"server":"http://a","username":"b"}}"""
        assertEquals(SyncSettings.ReadError.NewerVersion(2), readError(json.toByteArray()))
    }

    @Test
    fun testABadSectionLeavesTheOthers() {
        val json = """
            {"format":"hilltv.settings","version":1,
             "account":{"server":"http://line.example.com","username":"viewer"},
             "hiddenCategories":"garbage",
             "favorites":[{"stream_id":5,"name":"Good"},{"name":"no id"}],
             "channelOrder":{"12":[3,1]}}
        """.trimIndent()
        val read = SyncSettings.read(json.toByteArray())
        assertEquals(CategoryVisibility(), read.hiddenCategories)
        assertEquals(listOf(5), read.favorites.map { it.id })
        assertEquals(listOf(3, 1), read.channelOrder.lists["12"])
    }

    @Test
    fun testSameServer() {
        assertTrue(SyncSettings.sameServer("http://Line.Example.com:8080/", "line.example.com:8080"))
        assertTrue(SyncSettings.sameServer("http://line.example.com:8080/player_api.php", "http://line.example.com:8080"))
        assertFalse(SyncSettings.sameServer("http://line.example.com:8080", "http://line.example.com:8000"))
        assertFalse(SyncSettings.sameServer("http://a.example.com", "http://b.example.com"))
        assertFalse(SyncSettings.sameServer("", "http://a.example.com"))
    }

    @Test
    fun testSummary() {
        val file = sample()
        assertEquals("Loaded 3 hidden categories.", file.summary(SyncSettings.Choices()))
        assertEquals(
            "Loaded 3 hidden categories, 2 favorites and the order of 1 channel list.",
            file.summary(SyncSettings.Choices(hiddenCategories = true, favorites = true, channelOrder = true)),
        )
        assertEquals("Nothing was loaded.", file.summary(SyncSettings.Choices(hiddenCategories = false)))
    }

    // Android-only: the same file must move between the two apps.

    /** A file the Apple TV wrote, printed by the Swift core in the swift:6.2 image (Nov 2026). */
    @Test
    fun testReadsAFileTheAppleTVWrote() {
        val bytes = javaClass.getResourceAsStream("/swift-settings.json")!!.use { it.readBytes() }
        val read = SyncSettings.read(bytes)
        assertEquals(sampleAsSwiftWroteIt(), read)
    }

    private fun sampleAsSwiftWroteIt() = SyncSettings(
        account = SyncSettings.Account("http://line.example.com:8080", "viewer"),
        hiddenCategories = CategoryVisibility()
            .setHidden(true, listOf("1", "2"), CatalogKind.LIVE)
            .setHidden(true, listOf("9"), CatalogKind.MOVIES),
        favorites = listOf(LiveStream(id = 7, name = "US | CBS")),
        channelOrder = ChannelOrder().move(3, 0, listOf(1, 2, 3), "12"),
    )

    /** Hidden categories go under "hidden", the shape Swift writes, and not flat as this app's preferences keep them. */
    @Test
    fun testWritesHiddenCategoriesInTheAppleTVShape() {
        val json = Json.parseToJsonElement(sampleAsSwiftWroteIt().encoded().toString(Charsets.UTF_8)).jsonObject
        val hidden = json["hiddenCategories"]!!.jsonObject["hidden"]!!.jsonObject
        assertEquals(setOf("live", "movies"), hidden.keys)
        assertEquals("viewer", json["account"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertNull(json["account"]!!.jsonObject["password"])
    }

    /** The flat form (this app's preferences) is read too, in case a file was written by hand from them. */
    @Test
    fun testReadsTheFlatFormOfHiddenCategories() {
        val json = """{"format":"hilltv.settings","account":{"server":"http://a","username":"b"},"hiddenCategories":{"live":["4"]}}"""
        assertTrue(SyncSettings.read(json.toByteArray()).hiddenCategories.isHidden("4", CatalogKind.LIVE))
    }
}

class SyncHTTPTests {
    private fun read(vararg chunks: String): SyncRequestReader.Result {
        val reader = SyncRequestReader()
        var result: SyncRequestReader.Result = SyncRequestReader.Result.NeedMore
        for (chunk in chunks) result = reader.add(chunk.toByteArray())
        return result
    }

    @Test
    fun testRequestInPieces() {
        val result = read(
            "POST /api/sign-in?code=123456 HT",
            "TP/1.1\r\nHost: 192.168.1.20\r\nContent-Le",
            "ngth: 11\r\nX-HillTV-Code: 123456\r\n\r\nhello",
            " world",
        )
        val request = (result as? SyncRequestReader.Result.Done)?.request ?: return fail("$result")
        assertEquals("POST", request.method)
        assertEquals("/api/sign-in", request.path)
        assertEquals("123456", request.query["code"])
        assertEquals("123456", request.headers["x-hilltv-code"])
        assertEquals("hello world", request.body.toString(Charsets.UTF_8))
    }

    @Test
    fun testWaitsForTheWholeBody() {
        assertEquals(SyncRequestReader.Result.NeedMore, read("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"))
        assertEquals(SyncRequestReader.Result.NeedMore, read("GET / HTTP/1.1\r\nHost: a"))
    }

    @Test
    fun testBadRequests() {
        assertEquals(SyncRequestReader.Result.Failed(400), read("NONSENSE\r\n\r\n"))
        assertEquals(SyncRequestReader.Result.Failed(400), read("GET / HTTP/1.1\r\nContent-Length: -4\r\n\r\n"))
        assertEquals(SyncRequestReader.Result.Failed(413), read("POST / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n"))
        assertEquals(SyncRequestReader.Result.Failed(431), read("a".repeat(17_000)))
    }

    @Test
    fun testQueryDecoding() {
        assertEquals(
            mapOf("a" to "1", "b" to "two words", "c" to "/x", "d" to ""),
            SyncRequestReader.parseQuery("a=1&b=two+words&c=%2Fx&d"),
        )
    }

    @Test
    fun testResponseBytes() {
        val response = SyncResponse(200, "text/plain", "hi".toByteArray(), listOf("X-A" to "b"))
        val text = response.serialized().toString(Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.contains("Cache-Control: no-store\r\n"))
        assertTrue(text.contains("X-A: b\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhi"))
    }

    // Android-only: where Kotlin's own tools would differ from Foundation's.

    @Test
    fun testPathDecodingKeepsPlusAndBrokenEscapes() {
        val request = (read("GET /a+b%2Fc HTTP/1.1\r\n\r\n") as SyncRequestReader.Result.Done).request
        assertEquals("/a+b/c", request.path)
        val broken = (read("GET /api/%zz HTTP/1.1\r\n\r\n") as SyncRequestReader.Result.Done).request
        assertEquals("/api/%zz", broken.path)
        assertEquals(mapOf("q" to "%zz"), SyncRequestReader.parseQuery("q=%zz"))
    }

    @Test
    fun testALengthPastWhatALongHoldsIsUnreadable() {
        assertEquals(SyncRequestReader.Result.Failed(400), read("POST / HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n"))
        assertEquals(SyncRequestReader.Result.Failed(413), read("POST / HTTP/1.1\r\nContent-Length: 99999999999\r\n\r\n"))
    }
}

class SyncGateTests {
    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ) = SyncRequest(method, path, query, headers, body.toByteArray())

    private fun SyncRoute.reply(): SyncResponse = (this as? SyncRoute.Reply)?.response ?: error("not a reply: $this")

    @Test
    fun testThePageNeedsNoCode() {
        val gate = SyncGate("123456")
        assertEquals(SyncRoute.Page, gate.route(request("GET", "/")))
        assertEquals(SyncResponse.message("Not found.", false, 404), gate.route(request("GET", "/favicon.ico")).reply())
        assertEquals(0, gate.misses)
    }

    @Test
    fun testEveryAPIRequestNeedsTheCode() {
        val gate = SyncGate("123456")
        assertEquals(403, gate.route(request("GET", "/api/state")).reply().status)
        assertEquals(SyncRoute.State, gate.route(request("GET", "/api/state", headers = mapOf("x-hilltv-code" to "123456"))))
        assertEquals(SyncRoute.DownloadSettings, gate.route(request("GET", "/api/settings", query = mapOf("code" to "123456"))))
    }

    @Test
    fun testLocksAfterTooManyWrongCodes() {
        val gate = SyncGate("123456")
        repeat(SyncGate.ALLOWED_MISSES - 1) { gate.route(request("GET", "/api/state", query = mapOf("code" to "000000"))) }
        assertFalse(gate.isLocked)
        assertEquals(423, gate.route(request("GET", "/api/state", query = mapOf("code" to "111111"))).reply().status)
        assertTrue(gate.isLocked)
        // Even the right code is refused now.
        assertEquals(423, gate.route(request("GET", "/api/state", query = mapOf("code" to "123456"))).reply().status)
        // The page still loads, so it can say why.
        assertEquals(SyncRoute.Page, gate.route(request("GET", "/")))
    }

    @Test
    fun testSignIn() {
        val gate = SyncGate("1")
        val ok = gate.route(
            request("POST", "/api/sign-in", query = mapOf("code" to "1"),
                body = """{"server":" line.example.com ","username":"viewer","password":"p+ss w"}""")
        )
        assertEquals(SyncRoute.SignIn("line.example.com", "viewer", "p+ss w"), ok)
        assertEquals(400, gate.route(request("POST", "/api/sign-in", query = mapOf("code" to "1"), body = """{"server":"a"}""")).reply().status)
        assertEquals(405, gate.route(request("GET", "/api/sign-in", query = mapOf("code" to "1"))).reply().status)
    }

    @Test
    fun testUploadChoices() {
        val gate = SyncGate("1")
        val route = gate.route(
            request("POST", "/api/settings", query = mapOf("code" to "1", "hidden" to "1", "favorites" to "0", "order" to "1"), body = "{}")
        ) as SyncRoute.UploadSettings
        assertEquals("{}", route.data.toString(Charsets.UTF_8))
        assertEquals(SyncSettings.Choices(hiddenCategories = true, favorites = false, channelOrder = true), route.choices)
        assertEquals(400, gate.route(request("POST", "/api/settings", query = mapOf("code" to "1"), body = "{}")).reply().status)
    }

    @Test
    fun testCodeIsSixDigits() {
        repeat(50) {
            val code = SyncGate.makeCode()
            assertEquals(6, code.length)
            assertTrue(code.all { it in '0'..'9' })
        }
    }

    @Test
    fun testPageWritesNoValuesAsHTML() {
        assertFalse(SyncPage.html.contains("innerHTML"))
        assertFalse(SyncPage.html.contains("outerHTML"))
        assertFalse(SyncPage.html.contains("insertAdjacentHTML"))
        assertFalse(SyncPage.html.contains("document.write"))
        assertTrue(SyncPage.html.contains("textContent"))
    }

    /** A new TV is filled in from a saved file: the page reads the same format marker and account fields the file is written with. */
    @Test
    fun testPageReadsTheFileItSaves() {
        val json = Json.parseToJsonElement(
            SyncSettings(
                SyncSettings.Account("http://line.example.com", "viewer"),
                CategoryVisibility(), emptyList(), ChannelOrder(),
            ).encoded().toString(Charsets.UTF_8)
        ) as JsonObject
        assertEquals(SyncSettings.FORMAT, json["format"]!!.jsonPrimitive.content)
        assertEquals("http://line.example.com", json["account"]!!.jsonObject["server"]!!.jsonPrimitive.content)
        assertTrue(SyncPage.html.contains("data.format !== \"${SyncSettings.FORMAT}\""))
        assertTrue(SyncPage.html.contains("data.account.server"))
        assertTrue(SyncPage.html.contains("data.account.username"))
    }

    // Android-only: both TVs serve the same page.

    /** The page here is a copy of the one in SyncPage.swift. Change it there and copy it over, or this fails. */
    @Test
    fun testPageIsTheAppleTVPage() {
        val swift = File("../../Packages/HillTVCore/Sources/HillTVCore/SyncPage.swift")
        if (!swift.exists()) fail("SyncPage.swift not found at ${swift.absolutePath}")
        val source = swift.readText()
        val start = source.indexOf("#\"\"\"\n") + 5
        val end = source.indexOf("\n\"\"\"#")
        assertEquals(source.substring(start, end), SyncPage.html)
    }
}
