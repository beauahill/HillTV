package com.beauahill.hilltv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

// The small web server's request handling, for the phone page the TV serves
// while its Sync screen is open. Networking lives in the app; the parsing and
// the rules about who may do what live here, where they can be tested. The
// twin of SyncHTTP.swift.

/** One HTTP request, read from a connection. Header names are in lower case. */
class SyncRequest(
    val method: String,
    /** The path without the query, such as "/api/state". */
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** Collects a request as it arrives in pieces. */
class SyncRequestReader(val maximumBytes: Int = SyncSettings.MAXIMUM_BYTES + 16_384) {
    sealed interface Result {
        data object NeedMore : Result
        class Done(val request: SyncRequest) : Result
        /** The request can't be read; answer with this status and close. */
        data class Failed(val status: Int) : Result
    }

    private val buffer = ByteArrayOutputStream()

    fun add(data: ByteArray): Result {
        buffer.write(data)
        val bytes = buffer.toByteArray()
        val headerEnd = indexOf(bytes, HEADER_END)
        if (headerEnd < 0) return if (bytes.size > MAXIMUM_HEADER) Result.Failed(431) else Result.NeedMore
        val head = decodeUtf8(bytes, 0, headerEnd) ?: return Result.Failed(400)

        val lines = head.split("\r\n")
        val requestLine = lines[0].split(' ').filter { it.isNotEmpty() }
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return Result.Failed(400)

        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            headers[line.substring(0, colon).trim(' ', '\t').lowercase()] = line.substring(colon + 1).trim(' ', '\t')
        }

        // A Long, so a huge length reads as too large rather than unreadable.
        val length: Long = headers["content-length"]?.let { declared ->
            val value = declared.toLongOrNull()
            if (value == null || value < 0) return Result.Failed(400)
            value
        } ?: 0L
        if (length > maximumBytes) return Result.Failed(413)
        val bodyStart = headerEnd + HEADER_END.size
        if (bytes.size - bodyStart < length) return Result.NeedMore
        val body = bytes.copyOfRange(bodyStart, bodyStart + length.toInt())

        val target = requestLine[1]
        val question = target.indexOf('?')
        val rawPath = if (question < 0) target else target.substring(0, question)
        return Result.Done(
            SyncRequest(
                method = requestLine[0].uppercase(),
                path = percentDecode(rawPath) ?: rawPath,
                query = if (question < 0) emptyMap() else parseQuery(target.substring(question + 1)),
                headers = headers,
                body = body,
            )
        )
    }

    companion object {
        private val HEADER_END = "\r\n\r\n".toByteArray()
        private const val MAXIMUM_HEADER = 16_384

        /** `a=1&b=two+words`. A value that can't be decoded is kept as it came. */
        fun parseQuery(text: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (pair in text.split('&')) {
                if (pair.isEmpty()) continue
                val equals = pair.indexOf('=')
                val key = if (equals < 0) pair else pair.substring(0, equals)
                val value = if (equals < 0) "" else pair.substring(equals + 1)
                result[formDecode(key)] = formDecode(value)
            }
            return result
        }

        private fun formDecode(text: String): String {
            val spaced = text.replace('+', ' ')
            return percentDecode(spaced) ?: spaced
        }

        /**
         * `%2F` to `/`, as Foundation's `removingPercentEncoding` does: null
         * for a broken escape or bytes that aren't UTF-8, and "+" left alone.
         * Not `URLDecoder`, which turns "+" into a space and throws.
         */
        internal fun percentDecode(text: String): String? {
            if ('%' !in text) return text
            val out = ByteArrayOutputStream()
            var i = 0
            val source = text.toByteArray(Charsets.UTF_8)
            while (i < source.size) {
                val b = source[i]
                if (b == '%'.code.toByte()) {
                    if (i + 2 >= source.size) return null
                    val high = Character.digit(source[i + 1].toInt().toChar(), 16)
                    val low = Character.digit(source[i + 2].toInt().toChar(), 16)
                    if (high < 0 || low < 0) return null
                    out.write(high * 16 + low)
                    i += 3
                } else {
                    out.write(b.toInt())
                    i += 1
                }
            }
            val bytes = out.toByteArray()
            return decodeUtf8(bytes, 0, bytes.size)
        }

        private fun decodeUtf8(bytes: ByteArray, from: Int, to: Int): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, from, to - from))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}

/** One HTTP response. */
class SyncResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val extraHeaders: List<Pair<String, String>> = emptyList(),
) {
    /** The text of a JSON body, for tests and logs that never include the request. */
    val bodyText: String get() = body.toString(Charsets.UTF_8)

    /** The bytes to send. Every response closes the connection and is never cached. */
    fun serialized(): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 $status ${reason(status)}\r\n")
        head.append("Content-Type: $contentType\r\n")
        head.append("Content-Length: ${body.size}\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n")
        for ((name, value) in extraHeaders) head.append("$name: $value\r\n")
        head.append("\r\n")
        return head.toString().toByteArray(Charsets.UTF_8) + body
    }

    override fun equals(other: Any?): Boolean =
        other is SyncResponse && status == other.status && contentType == other.contentType &&
            body.contentEquals(other.body) && extraHeaders == other.extraHeaders

    override fun hashCode(): Int = status * 31 + body.contentHashCode()

    companion object {
        const val JSON_TYPE = "application/json; charset=utf-8"

        /** `{"message": …, "ok": …}`, which the page shows as plain text. */
        fun message(text: String, ok: Boolean, status: Int? = null): SyncResponse =
            json(buildJsonObject { put("message", text); put("ok", ok) }, status ?: if (ok) 200 else 400)

        fun json(value: JsonObject, status: Int = 200): SyncResponse =
            SyncResponse(status, JSON_TYPE, Json.encodeToString(JsonElement.serializer(), value).toByteArray(Charsets.UTF_8))

        internal fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            423 -> "Locked"
            431 -> "Request Header Fields Too Large"
            else -> if (status < 500) "Error" else "Server Error"
        }
    }
}

/** What a request asks for, once the code has been checked. */
sealed interface SyncRoute {
    /** The phone page itself. It holds nothing secret, so it needs no code. */
    data object Page : SyncRoute
    data object State : SyncRoute
    data class SignIn(val server: String, val username: String, val password: String) : SyncRoute {
        override fun toString(): String = "SignIn(server=$server, username=$username)"
    }
    data object DownloadSettings : SyncRoute
    class UploadSettings(val data: ByteArray, val choices: SyncSettings.Choices) : SyncRoute
    /** Ready to send as is: a refusal, a bad request or "not found". */
    data class Reply(val response: SyncResponse) : SyncRoute
}

/**
 * Decides what each request may do. Every /api request needs the code the TV
 * shows. After a few wrong codes the TV stops answering until its Sync screen
 * is opened again, so the code can't be guessed.
 *
 * Not thread-safe on purpose: the app routes every request on one thread, so
 * guesses made in parallel can't slip past the count.
 */
class SyncGate(val code: String) {
    var misses = 0
        private set

    val isLocked: Boolean get() = misses >= ALLOWED_MISSES

    fun route(request: SyncRequest): SyncRoute {
        if (request.method == "GET" && (request.path == "/" || request.path == "/index.html")) return SyncRoute.Page
        if (!request.path.startsWith("/api/")) return SyncRoute.Reply(SyncResponse.message("Not found.", false, 404))
        if (isLocked) return SyncRoute.Reply(SyncResponse.message(LOCKED, false, 423))
        val given = request.headers["x-hilltv-code"] ?: request.query["code"] ?: ""
        if (given != code) {
            misses += 1
            return SyncRoute.Reply(
                if (isLocked) {
                    SyncResponse.message(LOCKED, false, 423)
                } else {
                    SyncResponse.message("That code doesn't match the one on the TV.", false, 403)
                }
            )
        }

        return when (request.method to request.path) {
            "GET" to "/api/state" -> SyncRoute.State
            "POST" to "/api/sign-in" -> signIn(request.body)
            "GET" to "/api/settings" -> SyncRoute.DownloadSettings
            "POST" to "/api/settings" -> {
                val choices = SyncSettings.Choices(
                    hiddenCategories = request.query["hidden"] == "1",
                    favorites = request.query["favorites"] == "1",
                    channelOrder = request.query["order"] == "1",
                )
                if (choices.isEmpty) {
                    SyncRoute.Reply(SyncResponse.message("Tick at least one thing to load.", false))
                } else {
                    SyncRoute.UploadSettings(request.body, choices)
                }
            }
            else -> if (request.path in KNOWN_PATHS) {
                SyncRoute.Reply(SyncResponse.message("Not allowed.", false, 405))
            } else {
                SyncRoute.Reply(SyncResponse.message("Not found.", false, 404))
            }
        }
    }

    private fun signIn(body: ByteArray): SyncRoute {
        val missing = SyncRoute.Reply(SyncResponse.message("Fill in the server, username and password.", false))
        val o = try {
            Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return missing
        fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val server = string("server")?.trim() ?: return missing
        val username = string("username")?.trim() ?: return missing
        val password = string("password") ?: return missing
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) return missing
        return SyncRoute.SignIn(server, username, password)
    }

    companion object {
        const val ALLOWED_MISSES = 5
        private const val LOCKED = "Too many wrong codes. Close Sync on the TV and open it again."
        private val KNOWN_PATHS = setOf("/api/state", "/api/sign-in", "/api/settings")
        private val random = SecureRandom()

        /** A six-digit code for the TV to show. */
        fun makeCode(): String = "%06d".format(random.nextInt(1_000_000))
    }
}
