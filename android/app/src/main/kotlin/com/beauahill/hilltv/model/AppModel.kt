package com.beauahill.hilltv.model

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.BuildConfig
import com.beauahill.hilltv.core.LiveFormat
import com.beauahill.hilltv.core.Multiview
import com.beauahill.hilltv.core.PlaybackSettings
import com.beauahill.hilltv.core.XtreamAccount
import com.beauahill.hilltv.core.XtreamAuthInfo
import com.beauahill.hilltv.core.XtreamClient
import com.beauahill.hilltv.core.XtreamError
import com.beauahill.hilltv.player.PlayableItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val multiview = MultiviewStore()

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

    // MARK: Session

    suspend fun restore() {
        if (phase != Phase.LAUNCHING) return
        val account = withContext(Dispatchers.IO) { AccountStore.load(prefs) }
        if (account == null) {
            phase = Phase.SIGNED_OUT
            return
        }
        val client = XtreamClient(account, USER_AGENT)
        this.client = client
        watchHistory.activate(account)
        categoryVisibility.activate(account)
        channelOrder.activate(account)
        phase = Phase.SIGNED_IN

        // Refresh account details without holding up the UI.
        try {
            authInfo = client.authenticate()
            accountWarning = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            accountWarning = error.readable()
        }
    }

    suspend fun signIn(server: String, username: String, password: String) {
        val serverURL = XtreamAccount.normalizeServer(server) ?: throw XtreamError.InvalidServerURL
        val account = XtreamAccount(serverURL, username.trim(), password)
        val client = XtreamClient(account, USER_AGENT)
        val info = client.authenticate()

        withContext(Dispatchers.IO) { AccountStore.save(prefs, account) }
        watchHistory.activate(account)
        categoryVisibility.activate(account)
        channelOrder.activate(account)
        this.client = client
        authInfo = info
        accountWarning = null
        phase = Phase.SIGNED_IN
    }

    fun signOut() {
        AccountStore.delete(prefs)
        watchHistory.clear()
        // Hidden categories are kept for the next sign in; see CategoryVisibilityStore.
        categoryVisibility.deactivate()
        // The same goes for the order given to channel lists.
        channelOrder.deactivate()
        multiview.clear()
        nowPlaying = null
        client = null
        authInfo = null
        accountWarning = null
        phase = Phase.SIGNED_OUT
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
