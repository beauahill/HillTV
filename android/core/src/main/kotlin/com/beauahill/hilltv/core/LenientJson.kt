package com.beauahill.hilltv.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Xtream panels are inconsistent about JSON types. The same field can come back
// as "123", 123, 123.0, "", null, true or "1" depending on the panel software
// and version. These helpers accept all of them so one odd field never kills a
// whole channel list.
//
// This is the Kotlin twin of LenientDecoding.swift. Keep the two in step: the
// tests on both sides use the same payloads.

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = false }

/** Parses text into a JSON tree, or throws [XtreamError.Decoding]. */
internal fun parseJson(text: String): JsonElement =
    try {
        lenientJson.parseToJsonElement(text)
    } catch (error: Exception) {
        throw XtreamError.Decoding((error.message ?: error.toString()).take(300))
    }

private val plainNumber = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

private fun JsonObject.primitive(key: String): JsonPrimitive? {
    val value = this[key]
    return if (value is JsonPrimitive && value !is JsonNull) value else null
}

private fun String.toPlainDoubleOrNull(): Double? =
    if (plainNumber.matches(this)) toDoubleOrNull() else null

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.lenientString(key: String): String? {
    val p = primitive(key) ?: return null
    if (p.isString) return p.content.trim().ifEmpty { null }
    p.booleanOrNull?.let { return if (it) "1" else "0" }
    p.longOrNull?.let { return it.toString() }
    val d = p.doubleOrNull ?: return null
    return if (d.isFinite() && Math.rint(d) == d && Math.abs(d) < 9.0e18) d.toLong().toString() else d.toString()
}

internal fun JsonObject.lenientLong(key: String): Long? {
    val p = primitive(key) ?: return null
    if (p.isString) {
        val trimmed = p.content.trim()
        trimmed.toLongOrNull()?.let { return it }
        val d = trimmed.toPlainDoubleOrNull()
        return if (d != null && d.isFinite()) d.toLong() else null
    }
    p.booleanOrNull?.let { return if (it) 1 else 0 }
    p.longOrNull?.let { return it }
    val d = p.doubleOrNull
    return if (d != null && d.isFinite()) d.toLong() else null
}

/** As [lenientLong], but null when the value doesn't fit in an Int. */
internal fun JsonObject.lenientInt(key: String): Int? {
    val value = lenientLong(key) ?: return null
    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
}

internal fun JsonObject.lenientDouble(key: String): Double? {
    val p = primitive(key) ?: return null
    if (p.isString) return p.content.trim().toPlainDoubleOrNull()
    if (p.booleanOrNull != null) return null
    return p.doubleOrNull
}

internal fun JsonObject.lenientBool(key: String): Boolean {
    val p = primitive(key) ?: return false
    if (!p.isString) p.booleanOrNull?.let { return it }
    lenientLong(key)?.let { return it != 0L }
    if (p.isString) return p.content.trim().lowercase() in setOf("true", "yes", "y")
    return false
}

/** URLs stay strings in the core. Any non-empty value counts. */
internal fun JsonObject.lenientURL(key: String): String? = lenientString(key)

internal fun JsonObject.lenientStringArray(key: String): List<String> {
    val value = this[key]
    if (value is JsonArray) {
        val strings = value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        // One non-string element fails the whole array, as it does in Swift.
        return if (strings.size == value.size) strings else emptyList()
    }
    return lenientString(key)?.let { listOf(it) } ?: emptyList()
}

/**
 * Decodes an array but drops elements that fail instead of failing the whole
 * array. A non-array payload (`{}`, `null`, `false`), which some panels send
 * for "nothing here", decodes as empty.
 */
internal fun <T : Any> JsonElement?.lossyList(decode: (JsonObject) -> T?): List<T> {
    val array = this as? JsonArray ?: return emptyList()
    val result = ArrayList<T>(array.size)
    for (element in array) {
        val item = (element as? JsonObject)?.let(decode) ?: continue
        result.add(item)
    }
    return result
}
