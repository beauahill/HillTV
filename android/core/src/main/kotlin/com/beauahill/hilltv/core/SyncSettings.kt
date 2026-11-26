package com.beauahill.hilltv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The settings file the phone page saves from one TV and loads into another.
 *
 * One versioned JSON shape, written by the Apple TV and Google TV apps alike,
 * so a file saved from either loads into either. It never holds the
 * password: the file sits in the phone's downloads, and the login it names
 * is typed or autofilled on the phone instead.
 *
 * The shape is the Swift core's, as `JSONEncoder` writes it. Hidden
 * categories sit under a `hidden` key there (Swift's synthesized coding of
 * `CategoryVisibility`), which is not how this app keeps them in its own
 * preferences. So the file has its own writer and reader for that section,
 * and the reader takes the flat form too.
 */
data class SyncSettings(
    val account: Account,
    val hiddenCategories: CategoryVisibility,
    val favorites: List<LiveStream>,
    val channelOrder: ChannelOrder,
    val version: Int = CURRENT_VERSION,
) {
    data class Account(val server: String, val username: String)

    /** Why a file was refused. The message is shown on the phone as it is. */
    sealed class ReadError(message: String) : Exception(message) {
        data object TooLarge : ReadError("That file is too large to be a HillTV settings file.")
        data object NotASettingsFile : ReadError("That isn't a HillTV settings file.")
        data class NewerVersion(val version: Int) :
            ReadError("That file was saved by a newer HillTV (format $version). Update this TV's app first.")
    }

    /** Which parts of a loaded file to apply. */
    data class Choices(
        val hiddenCategories: Boolean = true,
        val favorites: Boolean = false,
        val channelOrder: Boolean = false,
    ) {
        val isEmpty: Boolean get() = !hiddenCategories && !favorites && !channelOrder
    }

    fun toJson(): JsonObject = buildJsonObject {
        // Server and username only. The password never goes in the file.
        put("account", buildJsonObject {
            put("server", account.server)
            put("username", account.username)
        })
        put("channelOrder", channelOrder.toJson())
        put("favorites", buildJsonArray { favorites.forEach { add(it.toJson()) } })
        put("format", FORMAT)
        put("hiddenCategories", buildJsonObject { put("hidden", hiddenCategories.toJson()) })
        put("version", version)
    }

    fun encoded(): ByteArray = prettyJson.encodeToString(JsonElement.serializer(), toJson()).toByteArray(Charsets.UTF_8)

    /** "Loaded 34 hidden categories and 12 favorites." for the phone and the TV. */
    fun summary(choices: Choices): String {
        val parts = ArrayList<String>()
        if (choices.hiddenCategories) {
            val count = CatalogKind.entries.sumOf { hiddenCategories.hiddenCount(it) }
            parts.add(if (count == 1) "1 hidden category" else "$count hidden categories")
        }
        if (choices.favorites) {
            parts.add(if (favorites.size == 1) "1 favorite" else "${favorites.size} favorites")
        }
        if (choices.channelOrder) {
            val lists = channelOrder.lists.size
            parts.add(if (lists == 1) "the order of 1 channel list" else "the order of $lists channel lists")
        }
        if (parts.isEmpty()) return "Nothing was loaded."
        val joined = if (parts.size > 1) parts.dropLast(1).joinToString(", ") + " and " + parts.last() else parts[0]
        return "Loaded $joined."
    }

    companion object {
        const val FORMAT = "hilltv.settings"
        const val CURRENT_VERSION = 1

        /** Larger than any real file; anything bigger is refused unread. */
        const val MAXIMUM_BYTES = 2_000_000

        private val prettyJson = Json { prettyPrint = true }

        /**
         * Reads a file the phone sent. It is untrusted: size, format and
         * version are checked, and each section decodes on its own, so one
         * bad section leaves the others usable.
         */
        fun read(data: ByteArray): SyncSettings {
            if (data.size > MAXIMUM_BYTES) throw ReadError.TooLarge
            val root = try {
                Json.parseToJsonElement(data.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                throw ReadError.NotASettingsFile
            }
            val o = root as? JsonObject ?: throw ReadError.NotASettingsFile
            if (o.string("format") != FORMAT) throw ReadError.NotASettingsFile
            val accountObject = o["account"] as? JsonObject ?: throw ReadError.NotASettingsFile
            val server = accountObject.string("server") ?: throw ReadError.NotASettingsFile
            val username = accountObject.string("username") ?: throw ReadError.NotASettingsFile

            val version = (o["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 1
            if (version > CURRENT_VERSION) throw ReadError.NewerVersion(version)

            return SyncSettings(
                account = Account(server, username),
                hiddenCategories = readHidden(o["hiddenCategories"]),
                favorites = o["favorites"].lossyList(LiveStream::fromJson),
                channelOrder = ChannelOrder.fromJson(o["channelOrder"]),
                version = version,
            )
        }

        /** `{"hidden": {...}}` as Swift writes it, or the flat form this app keeps in its preferences. */
        private fun readHidden(element: JsonElement?): CategoryVisibility {
            val o = element as? JsonObject ?: return CategoryVisibility()
            val inner = o["hidden"]
            return if (inner != null) {
                (inner as? JsonObject)?.let(CategoryVisibility::fromJson) ?: CategoryVisibility()
            } else {
                CategoryVisibility.fromJson(o)
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Category IDs only mean something on the provider they came from. */
        fun sameServer(a: String, b: String): Boolean {
            val left = XtreamAccount.normalizeServer(a) ?: return false
            val right = XtreamAccount.normalizeServer(b) ?: return false
            return left.lowercase() == right.lowercase()
        }
    }
}
