package com.beauahill.hilltv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class XtreamError(message: String) : Exception(message) {
    object InvalidServerURL : XtreamError("That server address doesn't look right.")
    object Unauthorized : XtreamError("The server rejected the username or password.")
    data class AccountInactive(val status: String) : XtreamError("The account status is $status.")
    data class HttpStatus(val code: Int) : XtreamError("The server returned HTTP $code.")
    data class Decoding(val detail: String) : XtreamError("Couldn't read the server's response. $detail")
    data class Network(val detail: String) : XtreamError(detail)
}

/**
 * Container format requested for live channels. HLS is the safe default.
 * Raw MPEG-TS starts faster, runs closer to the broadcast and is what most
 * panels serve natively.
 */
enum class LiveFormat(val ext: String) {
    HLS("m3u8"),
    MPEGTS("ts"),
}

class XtreamClient(
    val account: XtreamAccount,
    val userAgent: String = "HillTV/0.1",
    private val http: OkHttpClient = sharedHttp,
) {

    // MARK: API

    /** Calls player_api.php with no action. Returns account + server info. */
    suspend fun authenticate(): XtreamAuthInfo {
        val info = try {
            getTree(action = null, decode = XtreamAuthInfo::decode)
        } catch (_: XtreamError.Decoding) {
            // A bad login on many panels returns an empty body or "[]".
            throw XtreamError.Unauthorized
        }
        if (!info.user.auth) throw XtreamError.Unauthorized
        val status = info.user.status
        if (status != null && status.lowercase() != "active") throw XtreamError.AccountInactive(status)
        return info
    }

    suspend fun liveCategories(): List<XtreamCategory> = getList("get_live_categories", decode = XtreamCategory::fromJson)

    suspend fun liveStreams(categoryID: String? = null): List<LiveStream> =
        getList("get_live_streams", categoryQuery(categoryID), LiveStream::fromJson)

    suspend fun vodCategories(): List<XtreamCategory> = getList("get_vod_categories", decode = XtreamCategory::fromJson)

    suspend fun vodStreams(categoryID: String? = null): List<VODStream> =
        getList("get_vod_streams", categoryQuery(categoryID), VODStream::fromJson)

    suspend fun vodInfo(streamID: Int): VODInfo =
        getTree("get_vod_info", listOf("vod_id" to streamID.toString()), VODInfo::decode)

    suspend fun seriesCategories(): List<XtreamCategory> = getList("get_series_categories", decode = XtreamCategory::fromJson)

    suspend fun series(categoryID: String? = null): List<SeriesItem> =
        getList("get_series", categoryQuery(categoryID), SeriesItem::fromJson)

    suspend fun seriesInfo(seriesID: Int): SeriesInfo =
        getTree("get_series_info", listOf("series_id" to seriesID.toString()), SeriesInfo::decode)

    /** Now/next style listings for one channel. Cheap; fine to call on focus. */
    suspend fun shortEPG(streamID: Int, limit: Int = 4): List<EPGEntry> =
        getTree(
            "get_short_epg",
            listOf("stream_id" to streamID.toString(), "limit" to limit.toString()),
            EPGEntry::decodeShortEPG,
        )

    // MARK: Stream URLs

    fun liveURL(streamID: Int, format: LiveFormat): String = streamURL("live", streamID.toString(), format.ext)

    fun movieURL(movie: VODStream): String = movieURL(movie.id, movie.containerExtension)

    /**
     * For resuming from watch history, which stores IDs rather than URLs
     * (stream URLs embed the password).
     */
    fun movieURL(streamID: Int, containerExtension: String): String =
        streamURL("movie", streamID.toString(), containerExtension)

    fun episodeURL(episode: Episode): String = episodeURL(episode.id, episode.containerExtension)

    fun episodeURL(episodeID: String, containerExtension: String): String =
        streamURL("series", encodePathSegment(episodeID), containerExtension)

    /** Full XMLTV guide. Large (often 10-100 MB); download and parse off the main thread. */
    val xmltvURL: String
        get() = "${base()}/xmltv.php?${encodeQuery(credentialQuery)}"

    // MARK: Internals

    internal fun apiURL(action: String?, query: List<Pair<String, String>> = emptyList()): String {
        val items = credentialQuery.toMutableList()
        if (action != null) items.add("action" to action)
        items.addAll(query)
        return "${base()}/player_api.php?${encodeQuery(items)}"
    }

    internal fun streamURL(kind: String, id: String, ext: String): String {
        val user = encodePathSegment(account.username)
        val pass = encodePathSegment(account.password)
        // Built as a string so the credentials keep exactly the encoding above.
        return "${base()}/$kind/$user/$pass/$id.$ext"
    }

    private fun base(): String = account.serverURL.trimEnd('/')

    private val credentialQuery: List<Pair<String, String>>
        get() = listOf("username" to account.username, "password" to account.password)

    private fun categoryQuery(categoryID: String?): List<Pair<String, String>> =
        if (categoryID == null) emptyList() else listOf("category_id" to categoryID)

    /** Small responses: read the whole body, then decode from the tree. */
    private suspend fun <T> getTree(action: String?, query: List<Pair<String, String>> = emptyList(), decode: (JsonElement) -> T): T =
        open(action, query).use { response ->
            withContext(Dispatchers.IO) {
                val text = try {
                    response.body.string()
                } catch (error: IOException) {
                    throw XtreamError.Network(error.message ?: "The connection was lost.")
                }
                decode(parseJson(text))
            }
        }

    /**
     * Lists can be tens of megabytes. Rows are decoded one at a time off the
     * response stream, so memory holds the finished rows and never the whole
     * JSON tree. Bad rows are dropped; a non-array payload is an empty list.
     */
    private suspend fun <T : Any> getList(
        action: String,
        query: List<Pair<String, String>> = emptyList(),
        decode: (JsonObject) -> T?,
    ): List<T> =
        open(action, query).use { response ->
            withContext(Dispatchers.IO) { decodeLossyList(response.body.byteStream(), decode) }
        }

    private suspend fun open(action: String?, query: List<Pair<String, String>>): Response {
        val request = Request.Builder()
            .url(apiURL(action, query))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (error: IOException) {
            throw XtreamError.Network(error.message ?: "Couldn't reach the server.")
        } catch (error: IllegalArgumentException) {
            throw XtreamError.InvalidServerURL
        }
        if (!response.isSuccessful) {
            response.close()
            if (response.code == 401 || response.code == 403) throw XtreamError.Unauthorized
            throw XtreamError.HttpStatus(response.code)
        }
        return response
    }

    companion object {
        val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()
        }

        // Unreserved characters plus the ones URLComponents leaves alone in a
        // query item. "&", "=" and "+" are always escaped: PHP reads "+" as a
        // space, so a password containing one would silently break.
        private const val QUERY_ALLOWED = "-._~!$'()*,;:@/?"

        // What Foundation allows in a path, minus "/?#;" so a credential can
        // never be read as a separator.
        private const val SEGMENT_ALLOWED = "-._~!$&'()*+,=:@"

        internal fun encodeQuery(items: List<Pair<String, String>>): String =
            items.joinToString("&") { (name, value) ->
                "${percentEncode(name, QUERY_ALLOWED)}=${percentEncode(value, QUERY_ALLOWED)}"
            }

        internal fun encodePathSegment(value: String): String = percentEncode(value, SEGMENT_ALLOWED)

        private fun percentEncode(value: String, allowed: String): String {
            val out = StringBuilder(value.length + 8)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 0x80 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in allowed)) {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
                }
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        /**
         * Reads a JSON array off a stream row by row. Anything that is not an
         * array (`{}`, `null`, `false`, an empty body is an error) gives an
         * empty list. Broken JSON part way through throws [XtreamError.Decoding].
         */
        @OptIn(ExperimentalSerializationApi::class)
        internal fun <T : Any> decodeLossyList(stream: InputStream, decode: (JsonObject) -> T?): List<T> {
            val input = PushbackInputStream(stream.buffered(64 * 1024), 1)
            var first: Int
            try {
                do { first = input.read() } while (first == ' '.code || first == '\n'.code || first == '\r'.code || first == '\t'.code || first == 0xEF || first == 0xBB || first == 0xBF)
            } catch (error: IOException) {
                throw XtreamError.Network(error.message ?: "The connection was lost.")
            }
            if (first < 0) throw XtreamError.Decoding("The response was empty.")
            input.unread(first)
            if (first != '['.code) {
                // Not a list. Still has to be valid JSON to count as "nothing here".
                parseJson(input.readBytes().toString(Charsets.UTF_8))
                return emptyList()
            }
            val result = ArrayList<T>()
            try {
                for (element in lenientJson.decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                    val item = (element as? JsonObject)?.let(decode) ?: continue
                    result.add(item)
                }
            } catch (error: IOException) {
                throw XtreamError.Network(error.message ?: "The connection was lost.")
            } catch (error: XtreamError) {
                throw error
            } catch (error: Exception) {
                throw XtreamError.Decoding((error.message ?: error.toString()).take(300))
            }
            return result
        }
    }
}

/** Runs the call without blocking a thread; cancelling the coroutine cancels the request. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
