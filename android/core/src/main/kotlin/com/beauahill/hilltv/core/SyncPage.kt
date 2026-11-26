package com.beauahill.hilltv.core

/**
 * The page a phone opens after scanning the TV's QR code. Static: every value
 * the TV or a file supplies is put on the page as text by its script, never
 * as HTML.
 *
 * Kept in `resources/sync-page.html` and held by a test to the page in the
 * Swift core (`SyncPage.swift`), so both TVs serve the same page. Change it
 * there, then copy it here.
 */
object SyncPage {
    val html: String by lazy {
        val stream = SyncPage::class.java.getResourceAsStream("/sync-page.html")
            ?: error("sync-page.html is missing from the core's resources")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
