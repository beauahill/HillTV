package com.beauahill.hilltv.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBookTests {
    private fun account(user: String, server: String = "http://line.example.com:8080", password: String = "pw") =
        XtreamAccount(server, user, password)

    private fun roundTrip(book: AccountBook) = AccountBook.fromJson(Json.parseToJsonElement(book.toJson().toString()))

    @Test
    fun testKeyIsTheOneTheStoresAlreadyUse() {
        assertEquals("http://line.example.com:8080|viewer", AccountBook.key(account("viewer")))
    }

    @Test
    fun testMigratingKeepsTheOldLoginInUse() {
        val book = AccountBook.migrating(account("viewer"))
        assertEquals(listOf("viewer"), book.accounts.map { it.username })
        assertEquals("viewer", book.current?.username)
        assertTrue(AccountBook.migrating(null).isEmpty)
        assertNull(AccountBook.migrating(null).current)
    }

    @Test
    fun testAddingSwitchesToTheNewAccount() {
        val book = AccountBook.migrating(account("viewer")).upsert(account("viewer2"))
        assertEquals(listOf("viewer", "viewer2"), book.accounts.map { it.username })
        assertEquals("viewer2", book.current?.username)
    }

    @Test
    fun testSigningInAgainUpdatesThePasswordInPlace() {
        val book = AccountBook(listOf(account("viewer"), account("viewer2"))).upsert(account("viewer", password = "new"))
        assertEquals(listOf("viewer", "viewer2"), book.accounts.map { it.username })
        assertEquals("new", book.accounts[0].password)
        assertEquals("viewer", book.current?.username)
    }

    @Test
    fun testSelect() {
        var book = AccountBook(listOf(account("a"), account("b")))
        assertEquals("a", book.current?.username)
        book = book.select(AccountBook.key(account("b")))!!
        assertEquals("b", book.current?.username)
        assertNull(book.select("nope"))
        assertEquals("b", book.current?.username)
    }

    @Test
    fun testRemovingTheCurrentAccountMovesToTheFirstLeft() {
        var book = AccountBook(listOf(account("a"), account("b"), account("c")), AccountBook.key(account("b")))
        book = book.remove(AccountBook.key(account("b")))
        assertEquals(listOf("a", "c"), book.accounts.map { it.username })
        assertEquals("a", book.current?.username)
        book = book.remove(AccountBook.key(account("c")))
        assertEquals("a", book.current?.username)
        book = book.remove(AccountBook.key(account("a")))
        assertTrue(book.isEmpty)
        assertNull(book.currentKey)
    }

    @Test
    fun testAnEmptyBookStaysEmptyWhenSaved() {
        // Removing the last account saves an empty book, so the old login
        // can't come back on the next launch.
        val book = AccountBook.migrating(account("viewer")).remove(AccountBook.key(account("viewer")))
        assertTrue(roundTrip(book).isEmpty)
    }

    @Test
    fun testRoundTripAndLenientReading() {
        val other = account("b", "http://other.example.net")
        val book = AccountBook(listOf(account("a"), other), AccountBook.key(other))
        assertEquals(book, roundTrip(book))
        // A current key that isn't saved falls back to the first account.
        val odd = """{"accounts":[{"serverURL":"http://line.example.com","username":"a","password":"p"}],"currentKey":"gone"}"""
        assertEquals("a", AccountBook.fromJson(Json.parseToJsonElement(odd)).current?.username)
    }

    @Test
    fun testDuplicatesInTheSavedListCollapse() {
        val book = AccountBook(listOf(account("a"), account("a", password = "second")))
        assertEquals(1, book.accounts.size)
        assertEquals("second", book.accounts[0].password)
    }

    @Test
    fun testOthersOnTheSameServer() {
        val book = AccountBook(
            listOf(
                account("a"),
                account("b", "http://LINE.example.com:8080/"),
                account("c", "http://other.example.net"),
                account("d"),
            )
        )
        assertEquals(listOf("b", "d"), book.others(account("a")).map { it.username })
        assertEquals(emptyList<String>(), book.others(account("c", "http://other.example.net")).map { it.username })
    }

    @Test
    fun testLabelAndInitial() {
        assertEquals("viewer · line.example.com", account("viewer").label)
        assertEquals("V", account("viewer").initial)
        assertEquals("?", account("").initial)
    }

    // Android-only: the host comes from the address by hand, so these cover what java.net.URI would trip on.

    @Test
    fun testHostFromTheAddress() {
        assertEquals("line.example.com", account("a", "http://user@line.example.com:8080/panel").host)
        assertEquals("my_panel.example.com", account("a", "http://my_panel.example.com").host)
        assertEquals("::1", account("a", "http://[::1]:8080").host)
    }
}
