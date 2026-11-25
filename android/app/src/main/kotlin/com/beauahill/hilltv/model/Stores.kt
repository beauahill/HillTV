package com.beauahill.hilltv.model

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.core.CatalogKind
import com.beauahill.hilltv.core.CategoryVisibility
import com.beauahill.hilltv.core.ChannelOrder
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.Multiview
import com.beauahill.hilltv.core.MultiviewTiles
import com.beauahill.hilltv.core.WatchEntry
import com.beauahill.hilltv.core.WatchHistory
import com.beauahill.hilltv.core.XtreamAccount
import com.beauahill.hilltv.core.XtreamCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * Saves the Xtream login encrypted with a key held in the Android Keystore.
 * If the Keystore fails (some boxes have flaky ones), it falls back to the
 * app's private preferences so the box doesn't ask for a login every launch.
 * Backups are off in the manifest, so neither copy leaves the device.
 */
object AccountStore {
    private const val KEY_ALIAS = "com.beauahill.hilltv.account"
    private const val ENCRYPTED_KEY = "xtreamAccount.v1"
    private const val FALLBACK_KEY = "xtreamAccountFallback"
    private const val TAG = "AccountStore"

    fun load(prefs: SharedPreferences): XtreamAccount? {
        prefs.getString(ENCRYPTED_KEY, null)?.let { stored ->
            try {
                val (iv, data) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val json = cipher.doFinal(data).toString(Charsets.UTF_8)
                (parseOrNull(json) as? JsonObject)?.let(XtreamAccount::fromJson)?.let { return it }
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't read the saved login", error)
            }
        }
        return (parseOrNull(prefs.getString(FALLBACK_KEY, null)) as? JsonObject)?.let(XtreamAccount::fromJson)
    }

    fun save(prefs: SharedPreferences, account: XtreamAccount) {
        val json = account.toJson().toString()
        delete(prefs)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val data = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
            val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
            prefs.edit().putString(ENCRYPTED_KEY, stored).apply()
        } catch (error: Exception) {
            Log.w(TAG, "Keystore save failed; using the preferences fallback", error)
            prefs.edit().putString(FALLBACK_KEY, json).apply()
        }
    }

    fun delete(prefs: SharedPreferences) {
        prefs.edit().remove(ENCRYPTED_KEY).remove(FALLBACK_KEY).apply()
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

private fun accountKey(account: XtreamAccount): String = "${account.serverURL}|${account.username}"

/**
 * Favorite live channels, stored as small JSON in preferences. Channel lists
 * and guide data are too big for that; they stay in memory and are refetched.
 */
class FavoritesStore(private val prefs: SharedPreferences) {
    var channels: List<LiveStream> by mutableStateOf(load())
        private set

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

    private fun persist() {
        prefs.edit().putString(KEY, buildJsonArray { channels.forEach { add(it.toJson()) } }.toString()).apply()
    }

    private fun load(): List<LiveStream> =
        (parseOrNull(prefs.getString(KEY, null)) as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(LiveStream::fromJson) }
            ?: emptyList()

    private companion object {
        const val KEY = "favoriteChannels.v1"
    }
}

/**
 * Continue-watching state for the signed-in account. Tagged with server and
 * username so a different account never resumes the previous one's IDs, and
 * cleared on sign out.
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
        val stored = parseOrNull(prefs.getString(KEY, null)) as? JsonObject
        val storedKey = (stored?.get("accountKey") as? kotlinx.serialization.json.JsonPrimitive)?.content
        history = if (stored != null && storedKey == key) {
            stored["history"]?.let(WatchHistory::fromJson) ?: WatchHistory()
        } else {
            WatchHistory()
        }
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

    fun clear() {
        history = WatchHistory()
        accountKey = null
        prefs.edit().remove(KEY).apply()
    }

    private fun persist() {
        val key = accountKey ?: return
        val stored = buildJsonObject {
            put("accountKey", key)
            put("history", history.toJson())
        }
        prefs.edit().putString(KEY, stored.toString()).apply()
    }

    private companion object {
        const val KEY = "watchHistory.v1"
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
class MultiviewStore {
    var tiles: MultiviewTiles by mutableStateOf(MultiviewTiles())
        private set

    /** `MainShell` shows the multiview full screen while this is true. */
    var isShowing: Boolean by mutableStateOf(false)

    /** A short message shown over the multiview, such as "full". The screen clears it after a while. */
    var notice: String? by mutableStateOf(null)
        private set

    /**
     * A channel that would go past the connections the account reports.
     * The multiview asks the viewer about it, then adds or drops it.
     */
    var waiting: LiveStream? by mutableStateOf(null)
        private set

    /** From a hold-Select menu: adds the channel and opens the multiview. */
    fun addAndOpen(stream: LiveStream, limit: Int) {
        add(stream, limit)
        isShowing = true
    }

    /**
     * Adds a channel if there is room. When the account's reported
     * connections are what's in the way, the channel waits for the viewer's
     * answer instead; when the screen is full, it is dropped with a notice.
     */
    fun add(stream: LiveStream, limit: Int) {
        val (next, result) = tiles.add(stream, limit)
        tiles = next
        if (result != MultiviewTiles.AddResult.FULL) return
        if (limit < Multiview.MOST_TILES) {
            waiting = stream
        } else {
            notice = "Multiview shows four channels at most. Remove one to add another."
        }
    }

    /** The viewer said yes: add the waiting channel under the new limit. */
    fun addWaiting(limit: Int) {
        val stream = waiting ?: return
        waiting = null
        add(stream, limit)
    }

    /** The viewer said no, or closed the question. */
    fun dropWaiting() {
        waiting = null
    }

    fun remove(streamID: Int) {
        tiles = tiles.remove(streamID)
    }

    fun clearNotice() {
        notice = null
    }

    fun clear() {
        tiles = MultiviewTiles()
        isShowing = false
        notice = null
        waiting = null
    }
}
