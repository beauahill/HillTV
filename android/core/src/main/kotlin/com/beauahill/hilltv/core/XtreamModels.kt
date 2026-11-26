package com.beauahill.hilltv.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

// Kotlin twin of XtreamModels.swift. URLs are plain strings here.

// MARK: - Account

data class XtreamAccount(
    /** Scheme + host + optional port + optional base path, no trailing slash. Example: http://line.example.com:8080 */
    val serverURL: String,
    val username: String,
    val password: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("serverURL", serverURL)
        put("username", username)
        put("password", password)
    }

    companion object {
        private val urlShape = Regex("""^(https?)://([^/?#]*)([^?#]*)""", RegexOption.IGNORE_CASE)

        /**
         * Turns whatever the user typed ("host:8080", "http://host:8080/",
         * "https://host/panel/player_api.php?username=...") into a clean base URL.
         */
        fun normalizeServer(input: String): String? {
            var text = input.trim()
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                text = "http://$text"
            }
            val match = urlShape.find(text) ?: return null
            val (scheme, authority, rawPath) = match.destructured
            if (authority.any { it.isWhitespace() }) return null
            val host = authority.substringAfterLast('@').let { hostPort ->
                if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
            }
            if (host.isEmpty() || host == "[]") return null

            var path = rawPath
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php")) {
                if (path.lowercase().endsWith(suffix)) path = path.dropLast(suffix.length)
            }
            path = path.trimEnd('/')
            return "$scheme://$authority$path"
        }

        fun fromJson(o: JsonObject): XtreamAccount? {
            val server = o.lenientString("serverURL") ?: return null
            val username = o.lenientString("username") ?: return null
            val password = (o["password"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            return XtreamAccount(server, username, password)
        }
    }
}

// MARK: - Auth

data class XtreamAuthInfo(val user: UserInfo, val server: ServerInfo?) {
    val isAuthorized: Boolean get() = user.auth && (user.status?.lowercase() ?: "active") == "active"

    data class UserInfo(
        val username: String?,
        val status: String?,
        val auth: Boolean,
        val expiration: Instant?,
        val isTrial: Boolean,
        val activeConnections: Int?,
        val maxConnections: Int?,
        val allowedOutputFormats: List<String>,
    )

    data class ServerInfo(
        val url: String?,
        val port: String?,
        val httpsPort: String?,
        val serverProtocol: String?,
        val timezone: String?,
    )

    companion object {
        /** Throws [XtreamError.Decoding] when there is no `user_info` object. */
        fun decode(root: JsonElement): XtreamAuthInfo {
            val user = (root as? JsonObject)?.obj("user_info")
                ?: throw XtreamError.Decoding("user_info missing")
            val server = root.obj("server_info")
            return XtreamAuthInfo(
                user = UserInfo(
                    username = user.lenientString("username"),
                    status = user.lenientString("status"),
                    auth = user.lenientBool("auth"),
                    expiration = user.lenientLong("exp_date")?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
                    isTrial = user.lenientBool("is_trial"),
                    activeConnections = user.lenientInt("active_cons"),
                    maxConnections = user.lenientInt("max_connections"),
                    allowedOutputFormats = user.lenientStringArray("allowed_output_formats").map { it.lowercase() },
                ),
                server = server?.let {
                    ServerInfo(
                        url = it.lenientString("url"),
                        port = it.lenientString("port"),
                        httpsPort = it.lenientString("https_port"),
                        serverProtocol = it.lenientString("server_protocol"),
                        timezone = it.lenientString("timezone"),
                    )
                },
            )
        }
    }
}

// MARK: - Catalog

data class XtreamCategory(val id: String, val name: String, val parentID: String? = null) {
    companion object {
        fun fromJson(o: JsonObject): XtreamCategory? {
            val id = o.lenientString("category_id") ?: return null
            return XtreamCategory(id, o.lenientString("category_name") ?: "Untitled", o.lenientString("parent_id"))
        }
    }
}

data class LiveStream(
    val id: Int,
    val number: Int? = null,
    val name: String,
    val iconURL: String? = null,
    val epgChannelID: String? = null,
    val categoryID: String? = null,
    val hasArchive: Boolean = false,
    val archiveDays: Int? = null,
) {
    /** Uses the panel's own keys so favorites can be stored and read back. */
    fun toJson(): JsonObject = buildJsonObject {
        put("stream_id", id)
        number?.let { put("num", it) }
        put("name", name)
        iconURL?.let { put("stream_icon", it) }
        epgChannelID?.let { put("epg_channel_id", it) }
        categoryID?.let { put("category_id", it) }
        put("tv_archive", if (hasArchive) 1 else 0)
        archiveDays?.let { put("tv_archive_duration", it) }
    }

    companion object {
        fun fromJson(o: JsonObject): LiveStream? {
            val id = o.lenientInt("stream_id") ?: return null
            return LiveStream(
                id = id,
                number = o.lenientInt("num"),
                name = o.lenientString("name") ?: "Channel $id",
                iconURL = o.lenientURL("stream_icon"),
                epgChannelID = o.lenientString("epg_channel_id"),
                categoryID = o.lenientString("category_id"),
                hasArchive = o.lenientBool("tv_archive"),
                archiveDays = o.lenientInt("tv_archive_duration"),
            )
        }
    }
}

data class VODStream(
    val id: Int,
    val name: String,
    val posterURL: String?,
    val rating: Double?,
    val categoryID: String?,
    val containerExtension: String,
    val added: Instant?,
) {
    companion object {
        fun fromJson(o: JsonObject): VODStream? {
            val id = o.lenientInt("stream_id") ?: return null
            return VODStream(
                id = id,
                name = o.lenientString("name") ?: "Movie $id",
                posterURL = o.lenientURL("stream_icon"),
                rating = o.lenientDouble("rating"),
                categoryID = o.lenientString("category_id"),
                containerExtension = o.lenientString("container_extension")?.lowercase() ?: "mp4",
                added = o.lenientLong("added")?.let(Instant::ofEpochSecond),
            )
        }
    }
}

data class SeriesItem(
    val id: Int,
    val name: String,
    val coverURL: String?,
    val plot: String?,
    val rating: Double?,
    val categoryID: String?,
    val releaseDate: String?,
    val genre: String?,
) {
    companion object {
        fun fromJson(o: JsonObject): SeriesItem? {
            val id = o.lenientInt("series_id") ?: return null
            return SeriesItem(
                id = id,
                name = o.lenientString("name") ?: "Series $id",
                coverURL = o.lenientURL("cover"),
                plot = o.lenientString("plot"),
                rating = o.lenientDouble("rating"),
                categoryID = o.lenientString("category_id"),
                releaseDate = o.lenientString("releaseDate") ?: o.lenientString("release_date"),
                genre = o.lenientString("genre"),
            )
        }
    }
}

data class SeriesInfo(
    val name: String?,
    val plot: String?,
    val coverURL: String?,
    /** Episodes grouped by season number, seasons sorted ascending. */
    val seasons: List<Season>,
) {
    data class Season(val number: Int, val episodes: List<Episode>)

    companion object {
        fun decode(root: JsonElement): SeriesInfo {
            val o = root as? JsonObject ?: throw XtreamError.Decoding("series info is not an object")
            val info = o.obj("info")

            // Most panels send {"1": [...], "2": [...]}; some send [[...], [...]].
            val grouped = HashMap<Int, MutableList<Episode>>()
            when (val episodes = o["episodes"]) {
                is JsonObject -> for ((key, value) in episodes) {
                    val list = value.lossyList(Episode::fromJson)
                    val season = key.trim().toIntOrNull() ?: list.firstOrNull()?.season ?: 0
                    grouped.getOrPut(season) { mutableListOf() }.addAll(list)
                }
                is JsonArray -> episodes.forEachIndexed { index, value ->
                    val list = value.lossyList(Episode::fromJson)
                    val season = list.firstOrNull()?.season ?: (index + 1)
                    grouped.getOrPut(season) { mutableListOf() }.addAll(list)
                }
                else -> Unit
            }
            return SeriesInfo(
                name = info?.lenientString("name"),
                plot = info?.lenientString("plot"),
                coverURL = info?.lenientURL("cover"),
                seasons = grouped.keys.sorted().map { number ->
                    Season(number, grouped.getValue(number).sortedBy { it.episodeNumber ?: 0 })
                },
            )
        }
    }
}

data class Episode(
    /** Episode stream id. Xtream uses this (not series_id) in the play URL. */
    val id: String,
    val episodeNumber: Int?,
    val season: Int?,
    val title: String,
    val containerExtension: String,
    val plot: String?,
    val imageURL: String?,
    val durationSeconds: Int?,
) {
    companion object {
        fun fromJson(o: JsonObject): Episode? {
            val id = o.lenientString("id") ?: return null
            val episodeNumber = o.lenientInt("episode_num")
            val info = o.obj("info")
            return Episode(
                id = id,
                episodeNumber = episodeNumber,
                season = o.lenientInt("season"),
                title = o.lenientString("title") ?: "Episode ${episodeNumber?.toString() ?: id}",
                containerExtension = o.lenientString("container_extension")?.lowercase() ?: "mp4",
                plot = info?.lenientString("plot"),
                imageURL = info?.lenientURL("movie_image"),
                durationSeconds = info?.lenientInt("duration_secs"),
            )
        }
    }
}

// MARK: - EPG

/**
 * One programme from `get_short_epg`. Titles and descriptions arrive base64
 * encoded; this type decodes them.
 */
data class EPGEntry(
    val id: String,
    val title: String,
    val summary: String?,
    val start: Instant,
    val end: Instant,
) {
    fun isOnAir(at: Instant): Boolean = start <= at && at < end

    fun progress(at: Instant): Double {
        val total = seconds(start, end)
        if (total <= 0) return 0.0
        return (seconds(start, at) / total).coerceIn(0.0, 1.0)
    }

    companion object {
        fun fromJson(o: JsonObject): EPGEntry? {
            val start = o.lenientLong("start_timestamp")?.let(Instant::ofEpochSecond)
                ?: o.lenientString("start")?.let(::parsePanelDate)
            val end = o.lenientLong("stop_timestamp")?.let(Instant::ofEpochSecond)
                ?: o.lenientString("end")?.let(::parsePanelDate)
            if (start == null || end == null) return null
            val rawTitle = o.lenientString("title") ?: ""
            val title = decodeBase64(rawTitle) ?: rawTitle
            return EPGEntry(
                id = o.lenientString("id") ?: "${start.epochSecond}-$title",
                title = title,
                summary = o.lenientString("description")?.let { decodeBase64(it) ?: it },
                start = start,
                end = end,
            )
        }

        /** `{"epg_listings": [...]}`. Anything else is no listings. */
        fun decodeShortEPG(root: JsonElement): List<EPGEntry> =
            (root as? JsonObject)?.get("epg_listings").lossyList(::fromJson)

        /**
         * Null unless the text is padded base64 of valid UTF-8. Characters
         * outside the base64 alphabet are skipped first, as Foundation does.
         */
        internal fun decodeBase64(string: String): String? {
            if (string.isEmpty()) return null
            val cleaned = string.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
            if (cleaned.isEmpty() || cleaned.length % 4 != 0) return null
            val bytes = try {
                Base64.getDecoder().decode(cleaned)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }

        private val panelDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * Fallback for panels without timestamps. Assumes UTC, which is wrong for
         * some panels; the timestamp fields are preferred when present.
         */
        internal fun parsePanelDate(string: String): Instant? =
            try {
                LocalDateTime.parse(string, panelDate).toInstant(ZoneOffset.UTC)
            } catch (_: Exception) {
                null
            }
    }
}

/**
 * `get_vod_info`: details for one movie. Panels send `info` as an object,
 * or as `[]` when they have nothing, and spell several fields two ways.
 */
data class VODInfo(
    val name: String?,
    val plot: String?,
    val posterURL: String?,
    val backdropURL: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val durationSeconds: Int?,
    val rating: Double?,
    val containerExtension: String?,
) {
    /** First four digits of the release date, when it starts with a year. */
    val year: String?
        get() {
            val date = releaseDate ?: return null
            if (date.length < 4) return null
            val prefix = date.take(4)
            return if (prefix.all { it in '0'..'9' }) prefix else null
        }

    companion object {
        fun decode(root: JsonElement): VODInfo {
            val o = root as? JsonObject ?: throw XtreamError.Decoding("vod info is not an object")
            val data = o.obj("movie_data")
            val containerExtension = data?.lenientString("container_extension")?.lowercase()
            val info = o.obj("info")
                ?: return VODInfo(
                    name = data?.lenientString("name"),
                    plot = null, posterURL = null, backdropURL = null, genre = null, director = null, cast = null,
                    releaseDate = null, durationSeconds = null, rating = null,
                    containerExtension = containerExtension,
                )
            val rating = info.lenientDouble("rating")
            return VODInfo(
                name = info.lenientString("name") ?: data?.lenientString("name"),
                plot = info.lenientString("plot") ?: info.lenientString("description"),
                posterURL = info.lenientURL("movie_image") ?: info.lenientURL("cover_big"),
                backdropURL = info.lenientStringArray("backdrop_path").firstOrNull { it.isNotBlank() }?.trim(),
                genre = info.lenientString("genre"),
                director = info.lenientString("director"),
                cast = info.lenientString("cast") ?: info.lenientString("actors"),
                releaseDate = info.lenientString("releasedate") ?: info.lenientString("release_date"),
                durationSeconds = info.lenientInt("duration_secs") ?: parseDuration(info.lenientString("duration")),
                rating = if ((rating ?: 0.0) > 0) rating else null,
                containerExtension = containerExtension,
            )
        }

        /** "01:54:00" or "114" (minutes) to seconds. */
        internal fun parseDuration(string: String?): Int? {
            if (string == null) return null
            val parts = string.split(':').filter { it.isNotEmpty() }.map { it.toIntOrNull() }
            if (parts.isEmpty() || parts.any { it == null }) return null
            val values = parts.filterNotNull()
            return when (values.size) {
                1 -> values[0] * 60
                2 -> values[0] * 3600 + values[1] * 60
                else -> values[0] * 3600 + values[1] * 60 + values[2]
            }
        }
    }
}

/** Seconds from [from] to [to], with fractions. Negative when [to] is earlier. */
internal fun seconds(from: Instant, to: Instant): Double =
    (to.epochSecond - from.epochSecond).toDouble() + (to.nano - from.nano) / 1_000_000_000.0
