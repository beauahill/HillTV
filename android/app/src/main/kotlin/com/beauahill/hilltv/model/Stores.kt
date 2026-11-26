package com.beauahill.hilltv.model

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.core.AccountBook
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.CategoryVisibility
import com.beauahill.hilltv.core.ChannelOrder
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.Multiview
import com.beauahill.hilltv.core.MultiviewTiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.beauahill.hilltv.core.WatchEntry
import com.beauahill.hilltv.core.WatchHistory
import com.beauahill.hilltv.core.XtreamAccount
import com.beauahill.hilltv.core.XtreamCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data object Loaded : LoadState
    data object Empty : LoadState
    data class Failed(val message: String) : LoadState
}

internal fun parseOrNull(text: String?): JsonElement? =
    if (text == null) null else try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

/** What an error should say on screen. */
fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: "Something went wrong."

/**
 * Saves the logins (`AccountBook`) encrypted with a key held in the Android
 * Keystore. If the Keystore fails (some boxes have flaky ones), it falls back
 * to the app's private preferences so the box doesn't ask for a login every
 * launch. Backups are off in the manifest, so neither copy leaves the device.
 *
 * Before several accounts, one login was saved under different keys. The
 * first load after the update moves it into a book of one, and the same
 * write that saves the book removes the old login, so there is never a
 * moment with neither. From then on the book is always saved, even empty,
 * so a removed last account can't come back from the old keys.
 */
object AccountStore {
    private const val KEY_ALIAS = "com.beauahill.hilltv.account"
    private const val BOOK_KEY = "xtreamAccountBook.v1"
    private const val BOOK_FALLBACK_KEY = "xtreamAccountBookFallback"
    private const val LEGACY_KEY = "xtreamAccount.v1"
    private const val LEGACY_FALLBACK_KEY = "xtreamAccountFallback"
    private const val TAG = "AccountStore"

    fun loadBook(prefs: SharedPreferences): AccountBook {
        readText(prefs, BOOK_KEY, BOOK_FALLBACK_KEY)?.let { text ->
            parseOrNull(text)?.let { return AccountBook.fromJson(it) }
        }
        val legacy = readText(prefs, LEGACY_KEY, LEGACY_FALLBACK_KEY)
            ?.let { (parseOrNull(it) as? JsonObject)?.let(XtreamAccount::fromJson) }
        val book = AccountBook.migrating(legacy)
        if (legacy != null) save(prefs, book)
        return book
    }

    fun save(prefs: SharedPreferences, book: AccountBook) {
        val json = book.toJson().toString()
        val edit = prefs.edit()
            // The old single login is only ever read once, to migrate. It goes
            // in the same write that saves the book.
            .remove(LEGACY_KEY)
            .remove(LEGACY_FALLBACK_KEY)
        val sealed = try {
            seal(json)
        } catch (error: Exception) {
            Log.w(TAG, "Keystore save failed; using the preferences fallback", error)
            null
        }
        if (sealed != null) {
            edit.putString(BOOK_KEY, sealed).remove(BOOK_FALLBACK_KEY)
        } else {
            edit.remove(BOOK_KEY).putString(BOOK_FALLBACK_KEY, json)
        }
        edit.apply()
    }

    /** The encrypted copy if it can be read, otherwise the plain fallback. */
    private fun readText(prefs: SharedPreferences, encryptedKey: String, fallbackKey: String): String? {
        prefs.getString(encryptedKey, null)?.let { stored ->
            try {
                return open(stored)
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't read the saved logins", error)
            }
        }
        return prefs.getString(fallbackKey, null)
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun open(stored: String): String {
        val (iv, data) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/** Every per-account store is keyed by this. Its format must never change. */
private fun accountKey(account: XtreamAccount): String = AccountBook.key(account)

/**
 * Favorite live channels for the account in use, stored as small JSON in
 * preferences, one list per account. Channel lists and guide data are too
 * big for that; they stay in memory and are refetched.
 *
 * Favorites used to be one list for every account. The first account
 * activated after the update (the one in use) takes that list over. Nothing
 * is loaded or saved until an account is active.
 */
class FavoritesStore(private val prefs: SharedPreferences) {
    var channels: List<LiveStream> by mutableStateOf(emptyList())
        private set
    private var accountKey: String? = null

    fun activate(account: XtreamAccount) {
        val key = accountKey(account)
        accountKey = key
        val all = loadAll().toMutableMap()
        if (all[key] == null) {
            val legacy = parseOrNull(prefs.getString(LEGACY_KEY, null)) as? JsonArray
            if (legacy != null) {
                all[key] = legacy
                prefs.edit().putString(KEY, JsonObject(all).toString()).remove(LEGACY_KEY).apply()
            }
        }
        channels = (all[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(LiveStream::fromJson) } ?: emptyList()
    }

    fun deactivate() {
        accountKey = null
        channels = emptyList()
    }

    fun contains(streamID: Int): Boolean = channels.any { it.id == streamID }

    fun toggle(stream: LiveStream) {
        channels = if (contains(stream.id)) channels.filter { it.id != stream.id } else channels + stream
        persist()
    }

    /**
     * Puts a favorite at `index`. The order here is the order everywhere
     * favorites are listed: the Favorites page, Live TV, the Guide and Home.
     */
    fun move(streamID: Int, index: Int) {
        val from = channels.indexOfFirst { it.id == streamID }
        if (from < 0) return
        channels = ChannelOrder.moving(channels, from, index)
        persist()
    }

    /**
     * From a settings file the phone loaded. Replaces this account's
     * favorites, keeping the first of any channel listed twice.
     */
    fun replace(streams: List<LiveStream>) {
        val seen = HashSet<Int>()
        channels = streams.filter { seen.add(it.id) }
        persist()
    }

    private fun loadAll(): Map<String, JsonElement> =
        (parseOrNull(prefs.getString(KEY, null)) as? JsonObject) ?: emptyMap()

    private fun persist() {
        val key = accountKey ?: return
        val all = loadAll().toMutableMap()
        if (channels.isEmpty()) all.remove(key) else all[key] = buildJsonArray { channels.forEach { add(it.toJson()) } }
        prefs.edit().putString(KEY, JsonObject(all).toString()).apply()
    }

    private companion object {
        const val KEY = "favoriteChannels.v2"
        const val LEGACY_KEY = "favoriteChannels.v1"
    }
}

/**
 * Continue-watching state, one list per account, stored as small JSON in
 * preferences. Removing an account deletes its list.
 *
 * It used to be one list tagged with the account it belonged to. The first
 * launch after the update files that list under its account.
 */
class WatchHistoryStore(private val prefs: SharedPreferences) {
    var history: WatchHistory by mutableStateOf(WatchHistory())
        private set
    private var accountKey: String? = null

    val entries: List<WatchEntry> get() = history.entries

    fun entry(id: String): WatchEntry? = history.entry(id)

    fun activate(account: XtreamAccount) {
        val key = accountKey(account)
        accountKey = key
        val all = loadAll().toMutableMap()
        val legacy = parseOrNull(prefs.getString(LEGACY_KEY, null)) as? JsonObject
        if (legacy != null || prefs.contains(LEGACY_KEY)) {
            val legacyKey = (legacy?.get("accountKey") as? JsonPrimitive)?.content
            val legacyHistory = legacy?.get("history")
            if (legacyKey != null && legacyHistory != null && all[legacyKey] == null) all[legacyKey] = legacyHistory
            prefs.edit().putString(KEY, JsonObject(all).toString()).remove(LEGACY_KEY).apply()
        }
        history = all[key]?.let(WatchHistory::fromJson) ?: WatchHistory()
    }

    fun deactivate() {
        history = WatchHistory()
        accountKey = null
    }

    fun record(entry: WatchEntry) {
        if (accountKey == null) return
        history = history.record(entry)
        persist()
    }

    fun remove(id: String) {
        history = history.remove(id)
        persist()
    }

    /** For a removed account. */
    fun forget(accountKey: String) {
        val all = loadAll().toMutableMap()
        all.remove(accountKey)
        prefs.edit().putString(KEY, JsonObject(all).toString()).apply()
        if (this.accountKey == accountKey) history = WatchHistory()
    }

    private fun loadAll(): Map<String, JsonElement> =
        (parseOrNull(prefs.getString(KEY, null)) as? JsonObject) ?: emptyMap()

    private fun persist() {
        val key = accountKey ?: return
        val all = loadAll().toMutableMap()
        if (history.entries.isEmpty()) all.remove(key) else all[key] = history.toJson()
        prefs.edit().putString(KEY, JsonObject(all).toString()).apply()
    }

    private companion object {
        const val KEY = "watchHistory.v2"
        const val LEGACY_KEY = "watchHistory.v1"
    }
}

/**
 * The categories the user chose to hide, for the signed-in account.
 *
 * Stored as small JSON in preferences (IDs only), one entry per account.
 * Unlike watch history this is kept on sign out: hiding a few hundred
 * categories with a remote is not something to do twice. It lives on this
 * device only; nothing syncs between devices.
 */
class CategoryVisibilityStore(private val prefs: SharedPreferences) {
    var visibility: CategoryVisibility by mutableStateOf(CategoryVisibility())
        private set
    private var accountKey: String? = null

    fun activate(account: XtreamAccount) {
        val key = accountKey(account)
        accountKey = key
        visibility = loadAll()[key]?.let(CategoryVisibility::fromJson) ?: CategoryVisibility()
    }

    /** Signing out forgets who is active but leaves the saved choices alone. */
    fun deactivate() {
        accountKey = null
        visibility = CategoryVisibility()
    }

    fun isHidden(categoryID: String?, kind: CatalogKind): Boolean =
        categoryID != null && visibility.isHidden(categoryID, kind)

    fun visible(categories: List<XtreamCategory>, kind: CatalogKind): List<XtreamCategory> =
        visibility.visible(categories, kind)

    fun hiddenCount(kind: CatalogKind): Int = visibility.hiddenCount(kind)

    fun toggle(categoryID: String, kind: CatalogKind) {
        visibility = visibility.toggle(categoryID, kind)
        persist()
    }

    fun setHidden(isHidden: Boolean, ids: List<String>, kind: CatalogKind) {
        visibility = visibility.setHidden(isHidden, ids, kind)
        persist()
    }

    fun showAll(kind: CatalogKind) {
        visibility = visibility.showAll(kind)
        persist()
    }

    /** From a settings file the phone loaded. Replaces every choice. */
    fun replace(visibility: CategoryVisibility) {
        this.visibility = visibility
        persist()
    }

    private fun loadAll(): Map<String, JsonElement> =
        (parseOrNull(prefs.getString(KEY, null)) as? JsonObject) ?: emptyMap()

    private fun persist() {
        val key = accountKey ?: return
        val all = loadAll().toMutableMap()
        if (visibility == CategoryVisibility()) all.remove(key) else all[key] = visibility.toJson()
        prefs.edit().putString(KEY, JsonObject(all).toString()).apply()
    }

    private companion object {
        const val KEY = "hiddenCategories.v1"
    }
}

/**
 * The order the viewer gave their channel lists, for the signed-in account.
 *
 * Small JSON in preferences, one entry per account, kept on sign out like
 * hidden categories. `ChannelOrder` stores only the top of each list the
 * viewer has changed, so it stays small. Favorites are not in here: their
 * order is the order of `FavoritesStore.channels`.
 */
class ChannelOrderStore(private val prefs: SharedPreferences) {
    var order: ChannelOrder by mutableStateOf(ChannelOrder())
        private set
    private var accountKey: String? = null

    fun activate(account: XtreamAccount) {
        val key = accountKey(account)
        accountKey = key
        order = ChannelOrder.fromJson(loadAll()[key])
    }

    /** Signing out forgets who is active but leaves the saved order alone. */
    fun deactivate() {
        accountKey = null
        order = ChannelOrder()
    }

    fun hasOrder(categoryID: String): Boolean = order.hasOrder(categoryID)

    /** A category's channels in the viewer's order. */
    fun apply(streams: List<LiveStream>, categoryID: String): List<LiveStream> = order.apply(streams, categoryID)

    /** `shown` is the category's channels as they are listed now. */
    fun move(streamID: Int, index: Int, shown: List<LiveStream>, categoryID: String) {
        order = order.move(streamID, index, shown.map { it.id }, categoryID)
        persist()
    }

    fun reset(categoryID: String) {
        order = order.reset(categoryID)
        persist()
    }

    /** From a settings file the phone loaded. Replaces every saved order. */
    fun replace(order: ChannelOrder) {
        this.order = order
        persist()
    }

    private fun loadAll(): Map<String, JsonElement> =
        (parseOrNull(prefs.getString(KEY, null)) as? JsonObject) ?: emptyMap()

    private fun persist() {
        val key = accountKey ?: return
        val all = loadAll().toMutableMap()
        if (order.isEmpty) all.remove(key) else all[key] = order.toJson()
        prefs.edit().putString(KEY, JsonObject(all).toString()).apply()
    }

    private companion object {
        const val KEY = "channelOrder.v1"
    }
}

/**
 * The channels in the multiview and whether it is on screen. Kept for as
 * long as the app runs, so closing the multiview to find another channel
 * doesn't lose the ones already picked. Not saved between launches.
 */
class MultiviewStore(private val app: AppModel) {
    var tiles: MultiviewTiles by mutableStateOf(MultiviewTiles())
        private set

    /** `MainShell` shows the multiview full screen while this is true. */
    var isShowing: Boolean by mutableStateOf(false)

    /** A short message shown over the multiview, such as "full". The screen clears it after a while. */
    var notice: String? by mutableStateOf(null)
        private set

    /**
     * A channel that would go past the connections the accounts have free.
     * The multiview asks the viewer about it, then adds or drops it.
     */
    var waiting: LiveStream? by mutableStateOf(null)
        private set

    /** Adds asked of other accounts' panels run one at a time, so two can't take the same free connection. */
    private var lastAdd: Job? = null

    /** From a hold-Select menu: adds the channel and opens the multiview. */
    fun addAndOpen(stream: LiveStream, @Suppress("UNUSED_PARAMETER") limit: Int) {
        add(stream, limit)
        isShowing = true
    }

    /**
     * Adds a channel on an account with room: the one in use while it has
     * connections to spare (at once, so the first channel is there before the
     * multiview opens), else another saved account on the same server whose
     * panel says it has a connection free (asking takes a moment). When none
     * has room the channel waits for the viewer's answer, unless they already
     * chose "Try anyway". When the screen is full, it is dropped with a notice.
     *
     * `limit` is no longer used; the account in use and the others decide.
     */
    fun add(stream: LiveStream, @Suppress("UNUSED_PARAMETER") limit: Int) {
        if (tiles.contains(stream.id)) return
        if (tiles.streams.size >= Multiview.MOST_TILES) {
            notice = "Multiview shows four channels at most. Remove one to add another."
            return
        }
        if (lastAdd?.isActive != true) {
            app.ownAccountRoom(tiles)?.let { key ->
                tiles = tiles.add(stream, Multiview.MOST_TILES, key).first
                return
            }
        }
        val previous = lastAdd
        lastAdd = app.scope.launch {
            previous?.join()
            val accountInUse = app.currentAccountKey
            val own = app.ownAccountRoom(tiles)
            val pick = if (own != null) AppModel.MultiviewPick(own, emptyList()) else app.askOtherAccounts(tiles)
            // The answer took a moment: the account in use may have changed,
            // the picked one may have been removed, and the screen may be full.
            if (app.currentAccountKey != accountInUse || tiles.contains(stream.id) || tiles.streams.size >= Multiview.MOST_TILES) {
                return@launch
            }
            val key = pick.key?.takeIf(app::isSaved)
            when {
                key != null -> tiles = tiles.add(stream, Multiview.MOST_TILES, key).first
                app.multiviewIgnoresLimit -> {
                    tiles = tiles.add(stream, Multiview.MOST_TILES, app.currentAccountKey).first
                    // Otherwise the other accounts' not being used would look
                    // like the old one-account behaviour, with no reason given.
                    Multiview.borrowMessage(pick.refusals)?.let { notice = it }
                }
                else -> waiting = stream
            }
        }
    }

    /** The viewer said "Try anyway": the waiting channel plays on the account in use, past what it reports. */
    fun addWaiting(@Suppress("UNUSED_PARAMETER") limit: Int) {
        val stream = waiting ?: return
        waiting = null
        if (!tiles.contains(stream.id) && tiles.streams.size < Multiview.MOST_TILES) {
            tiles = tiles.add(stream, Multiview.MOST_TILES, app.currentAccountKey).first
        }
    }

    /** The viewer said no, or closed the question. */
    fun dropWaiting() {
        waiting = null
    }

    fun remove(streamID: Int) {
        tiles = tiles.remove(streamID)
    }

    /** For a removed account. */
    fun removeTiles(accountKey: String) {
        tiles = tiles.removeAll(accountKey)
    }

    fun clearNotice() {
        notice = null
    }

    fun clear() {
        lastAdd?.cancel()
        lastAdd = null
        tiles = MultiviewTiles()
        isShowing = false
        notice = null
        waiting = null
    }
}
