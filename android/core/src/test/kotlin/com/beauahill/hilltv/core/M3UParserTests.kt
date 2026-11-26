package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3UParserTests {
    @Test
    fun testParsesAttributesAndNames() {
        val playlist = """
        #EXTM3U x-tvg-url="http://epg.example/xmltv.xml"
        #EXTINF:-1 tvg-id="ktvq.us" tvg-name="KTVQ" tvg-logo="http://logo/ktvq.png" group-title="US, Locals",KTVQ CBS Billings
        http://line.example.com:8080/beau/pass/101.ts

        #EXTINF:-1 tvg-id="" group-title="Sports",ESPN, HD
        #EXTVLCOPT:http-user-agent=HillTV
        http://line.example.com:8080/beau/pass/102.ts
        #EXTINF:-1,No attributes
        #EXTGRP:Misc
        https://example.com/stream.m3u8
        """.trimIndent()
        val entries = M3UParser.parse(playlist)
        assertEquals(3, entries.size)

        assertEquals("KTVQ CBS Billings", entries[0].name)
        assertEquals("ktvq.us", entries[0].tvgID)
        assertEquals("US, Locals", entries[0].group)
        assertEquals("http://logo/ktvq.png", entries[0].logoURL)

        assertEquals("ESPN, HD", entries[1].name)
        assertNull(entries[1].tvgID)
        assertEquals("Sports", entries[1].group)

        assertEquals("No attributes", entries[2].name)
        assertEquals("Misc", entries[2].group)
    }
}
