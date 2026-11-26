package com.beauahill.hilltv.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The logins saved on this TV and which one is in use. The twin of
 * AccountBook.swift; immutable here, so each change returns a new book.
 *
 * Saved whole and encrypted (it holds passwords). Every per-account store
 * (watch history, favorites, hidden categories, channel order) is keyed by
 * [key], so the format of that key must never change.
 */
class AccountBook(accounts: List<XtreamAccount> = emptyList(), currentKey: String? = null) {
    val accounts: List<XtreamAccount>
    val currentKey: String?

    init {
        // Later entries with the same key replace earlier ones, keeping the earlier place.
        val byKey = LinkedHashMap<String, XtreamAccount>()
        for (account in accounts) byKey[key(account)] = account
        this.accounts = byKey.values.toList()
        this.currentKey = currentKey?.takeIf { it in byKey } ?: this.accounts.firstOrNull()?.let(::key)
    }

    val current: XtreamAccount? get() = accounts.firstOrNull { key(it) == currentKey }

    val isEmpty: Boolean get() = accounts.isEmpty()

    /**
     * Adds a login, or updates the saved one with the same server and
     * username (a new password), keeping its place in the list.
     */
    fun upsert(account: XtreamAccount, makeCurrent: Boolean = true): AccountBook {
        val key = key(account)
        val index = accounts.indexOfFirst { key(it) == key }
        val list = if (index >= 0) accounts.toMutableList().also { it[index] = account } else accounts + account
        return AccountBook(list, if (makeCurrent || currentKey == null) key else currentKey)
    }

    /** Null for a key that isn't saved. */
    fun select(key: String): AccountBook? =
        if (accounts.any { key(it) == key }) AccountBook(accounts, key) else null

    /** Removing the account in use moves to the first one left, or to none. */
    fun remove(key: String): AccountBook {
        val left = accounts.filter { key(it) != key }
        return AccountBook(left, currentKey?.takeIf { it != key })
    }

    /**
     * Other saved accounts on the same server as `account`, in list order.
     * Only those can play the same channels: stream IDs belong to a server.
     */
    fun others(onServerOf: XtreamAccount): List<XtreamAccount> {
        val key = key(onServerOf)
        return accounts.filter { key(it) != key && SyncSettings.sameServer(it.serverURL, onServerOf.serverURL) }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("accounts", buildJsonArray { accounts.forEach { add(it.toJson()) } })
        currentKey?.let { put("currentKey", it) }
    }

    override fun equals(other: Any?): Boolean =
        other is AccountBook && accounts == other.accounts && currentKey == other.currentKey

    override fun hashCode(): Int = accounts.hashCode() * 31 + (currentKey?.hashCode() ?: 0)

    override fun toString(): String = "AccountBook(${accounts.map { it.label }}, current=${current?.label})"

    companion object {
        /**
         * "http://line.example.com:8080|viewer". The same key the stores have
         * always used, so nothing saved before this book existed is lost.
         */
        fun key(account: XtreamAccount): String = "${account.serverURL}|${account.username}"

        /** The first launch after this update: the one login saved the old way becomes a book of one, in use. */
        fun migrating(legacy: XtreamAccount?): AccountBook = AccountBook(listOfNotNull(legacy))

        /** Lenient: bad entries are skipped, and a current key that isn't saved falls back to the first account. */
        fun fromJson(json: JsonElement?): AccountBook {
            val o = json as? JsonObject ?: return AccountBook()
            val accounts = o["accounts"].lossyList(XtreamAccount::fromJson)
            val current = (o["currentKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return AccountBook(accounts, current)
        }
    }
}

/** "viewer · line.example.com", enough to tell two subscriptions apart. */
val XtreamAccount.label: String get() = "$username · ${host ?: serverURL}"

/** The first letter of the username, for the rail's badge. */
val XtreamAccount.initial: String get() = username.firstOrNull()?.uppercase() ?: "?"

/** The host of the server address, without user info, port or brackets. */
val XtreamAccount.host: String?
    get() {
        val authority = serverURL.substringAfter("://", "").substringBefore('/').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
        return host.ifEmpty { null }
    }
