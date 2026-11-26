package com.beauahill.hilltv.model

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.BuildConfig
import com.beauahill.hilltv.core.AccountBook
import com.beauahill.hilltv.core.LiveFormat
import com.beauahill.hilltv.core.MultiviewTiles
import com.beauahill.hilltv.core.Multiview
import com.beauahill.hilltv.core.PlaybackSettings
import com.beauahill.hilltv.core.XtreamAccount
import com.beauahill.hilltv.core.XtreamAuthInfo
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.core.XtreamError
import com.beauahill.hilltv.player.PlayableItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** How the focused channel is shown in Live TV while browsing. */
enum class LivePreviewMode(val id: String, val displayName: String) {
    OFF("off", "Off"),
    MUTED("muted", "Picture only"),
    SOUND("sound", "Picture and sound"),
}

/** Session, settings and the stores that outlive a screen. One per process. */
class AppModel(context: Context) {
    enum class Phase { LAUNCHING, SIGNED_OUT, SIGNED_IN }

    private val prefs: SharedPreferences = context.getSharedPreferences("hilltv", Context.MODE_PRIVATE)

    var phase by mutableStateOf(Phase.LAUNCHING)
        private set
    var client: XtreamClient? by mutableStateOf(null)
        private set
    var authInfo: XtreamAuthInfo? by mutableStateOf(null)
        private set
    var accountWarning: String? by mutableStateOf(null)
        private set

    /** What the full-screen player is showing, or null when it is closed. */
    var nowPlaying: PlayableItem? by mutableStateOf(null)
        private set

    private val liveFormatState = mutableStateOf(
        LiveFormat.entries.firstOrNull { it.ext == prefs.getString(FORMAT_KEY, null) } ?: LiveFormat.HLS
    )

    /** The format picked in Settings. See [liveFormat] for what is actually requested. */
    var liveFormatChoice: LiveFormat
        get() = liveFormatState.value
        set(value) {
            liveFormatState.value = value
            prefs.edit().putString(FORMAT_KEY, value.ext).apply()
        }

    private val playbackState = mutableStateOf(
        parseOrNull(prefs.getString(PLAYBACK_KEY, null))?.let {
            try {
                PlaybackSettings.fromJson(it)
            } catch (_: Exception) {
                null
            }
        } ?: PlaybackSettings()
    )

    /** Live buffer and live delay. The defaults leave the player alone. */
    var playback: PlaybackSettings
        get() = playbackState.value
        set(value) {
            playbackState.value = value
            prefs.edit().putString(PLAYBACK_KEY, value.toJson().toString()).apply()
        }

    private val previewState = mutableStateOf(
        LivePreviewMode.entries.firstOrNull { it.id == prefs.getString(PREVIEW_KEY, null) }
    )

    /** What the user picked in Settings, or null if they haven't. */
    var previewChoice: LivePreviewMode?
        get() = previewState.value
        set(value) {
            previewState.value = value
            prefs.edit().putString(PREVIEW_KEY, value?.id).apply()
        }

    private val multiviewLimitState = mutableStateOf(prefs.getBoolean(MULTIVIEW_LIMIT_KEY, false))

    /**
     * The viewer chose to let the multiview try more channels than the
     * account reports connections for. Off unless they turn it on.
     */
    var multiviewIgnoresLimit: Boolean
        get() = multiviewLimitState.value
        set(value) {
            multiviewLimitState.value = value
            prefs.edit().putBoolean(MULTIVIEW_LIMIT_KEY, value).apply()
        }

    private val multiviewTSState = mutableStateOf(prefs.getBoolean(MULTIVIEW_TS_KEY, false))

    /**
     * A trial: multiview tiles ask for raw MPEG-TS instead of HLS. Off
     * unless the viewer turns it on.
     */
    var multiviewUsesTS: Boolean
        get() = multiviewTSState.value
        set(value) {
            multiviewTSState.value = value
            prefs.edit().putBoolean(MULTIVIEW_TS_KEY, value).apply()
        }

    val favorites = FavoritesStore(prefs)
    val watchHistory = WatchHistoryStore(prefs)
    val categoryVisibility = CategoryVisibilityStore(prefs)
    val channelOrder = ChannelOrderStore(prefs)
    val multiview = MultiviewStore(this)

    /** The phone page's web server, for Settings > Sync and the login screen. */
    val sync = SyncServer(this)

    /** `HillTVRoot` shows the Sync screen while this is true. */
    var syncShowing: Boolean by mutableStateOf(false)

    /**
     * Whether Live TV plays the focused channel in its detail pane.
     *
     * A preview holds a connection, and panels can take a few seconds to
     * release one after it stops. So it only defaults on when the account
     * clearly has a connection to spare.
     */
    val previewMode: LivePreviewMode
        get() = previewChoice ?: if (hasSpareConnection) LivePreviewMode.MUTED else LivePreviewMode.OFF

    /** False when the panel allows one connection or doesn't say. */
    val hasSpareConnection: Boolean get() = (authInfo?.user?.maxConnections ?: 1) >= 2

    /**
     * How many channels the multiview may play together: the account's
     * connection count, four at most, unless the viewer chose to try more.
     * A panel that doesn't say gets four, unlike [hasSpareConnection].
     */
    val multiviewLimit: Int
        get() = Multiview.tileLimit(authInfo?.user?.maxConnections, multiviewIgnoresLimit)

    /** Whether the MPEG-TS trial can be offered: the panel offers TS, or doesn't say what it offers. */
    val multiviewTSAvailable: Boolean
        get() {
            val formats = authInfo?.user?.allowedOutputFormats ?: emptyList()
            return formats.isEmpty() || "ts" in formats
        }

    /** True when the multiview's tiles ask for MPEG-TS. */
    val multiviewPlaysTS: Boolean get() = multiviewUsesTS && multiviewTSAvailable

    /** The connection count the panel reports, when it reports one. */
    val reportedConnections: Int? get() = authInfo?.user?.maxConnections

    /** The preview always plays HLS. */
    val previewSupported: Boolean
        get() {
            val formats = authInfo?.user?.allowedOutputFormats ?: emptyList()
            return formats.isEmpty() || "m3u8" in formats
        }

    /** Container to request for live channels: the choice, unless the panel doesn't offer it. */
    val liveFormat: LiveFormat
        get() {
            val allowed = authInfo?.user?.allowedOutputFormats ?: emptyList()
            val choice = liveFormatChoice
            if (allowed.isEmpty() || choice.ext in allowed) return choice
            return LiveFormat.entries.firstOrNull { it.ext in allowed } ?: choice
        }

    // MARK: Accounts

    /** For work that outlives a screen, such as asking another account's panel about a multiview channel. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The logins saved on this TV and which one is in use. */
    var book: AccountBook by mutableStateOf(AccountBook())
        private set

    /** The logins saved on this TV, in the order they were added. */
    val accounts: List<XtreamAccount> get() = book.accounts

    /** The key of the account in use (`AccountBook.key`). */
    val currentAccountKey: String? get() = book.currentKey

    /** The login screen is showing to add another account, over a TV that is signed in. Back or Cancel there goes back. */
    var addingAccount: Boolean by mutableStateOf(false)

    /** Account details by account key, so a switch shows the right connection count and formats straight away. */
    private val authCache = mutableStateMapOf<String, XtreamAuthInfo>()

    /** Clients for other saved accounts, made when a channel first plays on one. */
    private val otherClients = HashMap<String, XtreamClient>()

    /** The last account details seen for a saved account, if any. */
    fun accountDetails(key: String): XtreamAuthInfo? = authCache[key]

    /** Other saved accounts on the same server as the one in use. */
    val otherAccountsOnServer: List<XtreamAccount>
        get() = client?.account?.let(book::others) ?: emptyList()

    // MARK: Session

    /** Activates the saved account in use, or shows the login screen. */
    suspend fun restore() {
        if (phase != Phase.LAUNCHING) return
        book = withContext(Dispatchers.IO) { AccountStore.loadBook(prefs) }
        val account = book.current
        if (account == null) {
            phase = Phase.SIGNED_OUT
            return
        }
        activate(account)
        refreshAccountDetails()
    }

    /**
     * Adds a login (or updates the saved one with the same server and
     * username) and switches to it. Used by the login screen, Add an account
     * and the phone page.
     */
    suspend fun signIn(server: String, username: String, password: String) {
        val serverURL = XtreamAccount.normalizeServer(server) ?: throw XtreamError.InvalidServerURL
        val account = XtreamAccount(serverURL, username.trim(), password)
        val info = XtreamClient(account, USER_AGENT).authenticate()

        val key = AccountBook.key(account)
        book = book.upsert(account)
        withContext(Dispatchers.IO) { AccountStore.save(prefs, book) }
        authCache[key] = info
        // A client made with the old password, if this login was saved before.
        otherClients.remove(key)
        activate(account)
        addingAccount = false
    }

    /** Switches to another saved account. Its details are refreshed in the background; until then the last ones seen are used. */
    fun switchTo(key: String) {
        if (key == book.currentKey) return
        val next = book.select(key) ?: return
        book = next
        AccountStore.save(prefs, book)
        activate(next.current ?: return)
        scope.launch { refreshAccountDetails() }
    }

    /**
     * Deletes a saved login and its continue-watching list. Its favorites,
     * hidden categories and channel order are kept, so adding it again brings
     * them back. Removing the account in use moves to the first one left, or
     * to the login screen.
     */
    fun removeAccount(key: String) {
        val wasCurrent = key == book.currentKey
        book = book.remove(key)
        AccountStore.save(prefs, book)
        watchHistory.forget(key)
        authCache.remove(key)
        otherClients.remove(key)
        // Its channels would otherwise play on the account in use.
        multiview.removeTiles(key)
        if (!wasCurrent) return
        val next = book.current
        if (next != null) {
            activate(next)
            scope.launch { refreshAccountDetails() }
        } else {
            deactivate()
        }
    }

    /** Settings' button: removes the account in use. */
    fun signOut() {
        book.currentKey?.let(::removeAccount)
    }

    /** The one place an account becomes the one in use, so every store follows it. */
    private fun activate(account: XtreamAccount) {
        val key = AccountBook.key(account)
        watchHistory.activate(account)
        categoryVisibility.activate(account)
        channelOrder.activate(account)
        favorites.activate(account)
        // Its channels belong to the account that was in use.
        multiview.clear()
        nowPlaying = null
        client = XtreamClient(account, USER_AGENT)
        // The last details seen for this account, never the previous one's.
        authInfo = authCache[key]
        accountWarning = null
        phase = Phase.SIGNED_IN
    }

    private fun deactivate() {
        watchHistory.deactivate()
        // Hidden categories, channel order and favorites stay saved per account.
        categoryVisibility.deactivate()
        channelOrder.deactivate()
        favorites.deactivate()
        multiview.clear()
        nowPlaying = null
        client = null
        authInfo = null
        accountWarning = null
        addingAccount = false
        phase = Phase.SIGNED_OUT
    }

    /**
     * Account details (connections, formats, expiry) for the account in use,
     * without holding up the UI. A late answer for an account that is no
     * longer in use is kept for later but not shown.
     */
    private suspend fun refreshAccountDetails() {
        val client = client ?: return
        val key = AccountBook.key(client.account)
        try {
            val info = client.authenticate()
            authCache[key] = info
            if (this.client === client) {
                authInfo = info
                accountWarning = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (this.client === client) accountWarning = error.readable()
        }
    }

    // MARK: Multiview across accounts

    /** Where a new multiview channel plays, and why no other account was used when none was. */
    class MultiviewPick(val key: String?, val refusals: List<Multiview.BorrowRefusal>)

    /**
     * The account in use while it has connections to spare. Null when it is
     * at its limit and the other accounts on its server have to be asked
     * (see [askOtherAccounts]). Never suspends, so the first channel is in
     * the multiview before the multiview opens.
     */
    fun ownAccountRoom(tiles: MultiviewTiles): String? {
        val current = client?.account?.let(AccountBook::key) ?: return null
        val playing = tiles.countByAccount[current] ?: 0
        return if (playing < Multiview.ownLimit(authInfo?.user?.maxConnections)) current else null
    }

    /**
     * Another saved account on the same server that its panel says has a
     * connection free, asked now: someone else may have started watching on
     * it. Otherwise no account, with why each other account wasn't used.
     */
    suspend fun askOtherAccounts(tiles: MultiviewTiles): MultiviewPick {
        val account = client?.account ?: return MultiviewPick(null, emptyList())
        val playing = tiles.countByAccount
        val refusals = ArrayList<Multiview.BorrowRefusal>()
        for (other in book.others(account)) {
            val key = AccountBook.key(other)
            val info = try {
                clientFor(key)?.authenticate()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (info == null) {
                refusals.add(Multiview.BorrowRefusal.SIGN_IN_FAILED)
                continue
            }
            authCache[key] = info
            when {
                info.user.maxConnections == null || info.user.activeConnections == null ->
                    refusals.add(Multiview.BorrowRefusal.NOT_REPORTED)
                Multiview.hasFreeConnection(info.user.maxConnections, info.user.activeConnections, playing[key] ?: 0) ->
                    return MultiviewPick(key, emptyList())
                else -> refusals.add(Multiview.BorrowRefusal.NONE_FREE)
            }
        }
        return MultiviewPick(null, refusals)
    }

    /** True while `key` is still a saved login. */
    fun isSaved(key: String): Boolean = book.accounts.any { AccountBook.key(it) == key }

    /** The client a multiview channel plays through: the account in use, or another saved one; null for one since removed. */
    fun clientFor(key: String?): XtreamClient? {
        val client = client ?: return null
        if (key == null || key == AccountBook.key(client.account)) return client
        otherClients[key]?.let { return it }
        val account = book.accounts.firstOrNull { AccountBook.key(it) == key } ?: return null
        return XtreamClient(account, USER_AGENT).also { otherClients[key] = it }
    }

    /** A short message shown over the screen for a moment, like "Added to Favorites". */
    var notice: String? by mutableStateOf(null)
        private set

    fun flash(message: String) {
        notice = message
    }

    fun clearNotice() {
        notice = null
    }

    // MARK: Player

    fun play(item: PlayableItem) {
        nowPlaying = item
    }

    fun closePlayer() {
        nowPlaying = null
    }

    /** Saves how far a movie or episode got. Live items have no history. */
    fun recordProgress(item: PlayableItem, position: Double, duration: Double?) {
        val template = item.history ?: return
        if (item.isLive) return
        watchHistory.record(template.copy(position = position, duration = duration, updatedAt = Instant.ofEpochMilli(System.currentTimeMillis())))
    }

    companion object {
        val USER_AGENT: String = "HillTV/${BuildConfig.VERSION_NAME}"

        private const val FORMAT_KEY = "liveFormat"
        private const val PREVIEW_KEY = "livePreview"
        private const val PLAYBACK_KEY = "playbackSettings.v1"
        private const val MULTIVIEW_LIMIT_KEY = "multiviewIgnoresLimit"
        private const val MULTIVIEW_TS_KEY = "multiviewUsesTS"
    }
}
