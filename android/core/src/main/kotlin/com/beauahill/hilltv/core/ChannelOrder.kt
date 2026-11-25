package com.beauahill.hilltv.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The viewer's own order for channel lists, by provider category.
 *
 * Stored as small JSON next to the other settings, so it keeps as little
 * as it can: for each list, only the channel IDs from the top down to the
 * lowest row a move has touched. Everything below that follows the
 * provider's order. Moving a few channels to the top of a category of a
 * thousand stores a few IDs, not a thousand.
 *
 * A channel the provider drops loses its place. A channel the provider
 * adds appears below the stored part, where the provider put it.
 *
 * Immutable; each change returns a copy.
 */
data class ChannelOrder(
    /** List key (a provider category ID) to the IDs at the top of that list. */
    val lists: Map<String, List<Int>> = emptyMap(),
) {
    val isEmpty: Boolean get() = lists.isEmpty()

    /** Whether the viewer has changed this list's order. */
    fun hasOrder(key: String): Boolean = lists[key] != null

    /** `items` in the viewer's order: the stored IDs first, then the rest as given. */
    fun <Item> apply(items: List<Item>, key: String, id: (Item) -> Int): List<Item> {
        val stored = lists[key]
        if (stored.isNullOrEmpty()) return items
        val byID = HashMap<Int, Item>()
        for (item in items) {
            val itemID = id(item)
            if (!byID.containsKey(itemID)) byID[itemID] = item
        }
        val placed = HashSet<Int>()
        val result = ArrayList<Item>(items.size)
        for (storedID in stored) {
            if (byID.containsKey(storedID) && placed.add(storedID)) {
                @Suppress("UNCHECKED_CAST")
                result.add(byID[storedID] as Item)
            }
        }
        for (item in items) {
            if (id(item) !in placed) result.add(item)
        }
        return result
    }

    fun apply(streams: List<LiveStream>, key: String): List<LiveStream> = apply(streams, key) { it.id }

    /**
     * Moves one channel so it ends up at `index`.
     *
     * `shown` is the list's IDs in the order the viewer sees them, which is
     * [apply] of the provider's list.
     */
    fun move(id: Int, index: Int, shown: List<Int>, key: String): ChannelOrder {
        val from = shown.indexOf(id)
        if (from < 0) return this
        val to = index.coerceIn(0, shown.size - 1)
        if (from == to) return this
        val moved = moving(shown, from, to)
        // The part already stored sits at the top of `shown`; keep all of it.
        val present = shown.toSet()
        val alreadyStored = (lists[key] ?: emptyList()).count { it in present }
        val length = maxOf(alreadyStored, maxOf(from, to) + 1)
        return ChannelOrder(lists + (key to moved.take(length)))
    }

    /** Back to the provider's order. */
    fun reset(key: String): ChannelOrder = if (key in lists) ChannelOrder(lists - key) else this

    fun toJson(): JsonElement = buildJsonObject {
        for ((key, ids) in lists) {
            put(key, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        /** Something else under the same key reads as "no order", not a failure. */
        fun fromJson(json: JsonElement?): ChannelOrder {
            val root = json as? JsonObject ?: return ChannelOrder()
            val lists = LinkedHashMap<String, List<Int>>()
            for ((key, value) in root) {
                val array = value as? JsonArray ?: return ChannelOrder()
                lists[key] = array.map { wholeNumber(it) ?: return ChannelOrder() }
            }
            return ChannelOrder(lists)
        }

        private fun wholeNumber(element: JsonElement): Int? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            primitive.content.toIntOrNull()?.let { return it }
            val number = primitive.content.toDoubleOrNull() ?: return null
            return if (number == Math.rint(number) && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) number.toInt() else null
        }

        /** `items` with the element at `from` taken out and put back at `to`. */
        fun <Item> moving(items: List<Item>, from: Int, to: Int): List<Item> {
            if (from !in items.indices) return items
            val result = items.toMutableList()
            val item = result.removeAt(from)
            result.add(to.coerceIn(0, result.size), item)
            return result
        }
    }
}
