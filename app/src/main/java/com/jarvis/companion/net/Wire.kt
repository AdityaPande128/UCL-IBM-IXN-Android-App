package com.jarvis.companion.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Small readers over the daemon's loose JSON; unknown fields and unknown
// message types simply read as null and get ignored, so daemon evolution
// never crashes the phone.
fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun msg(type: String, vararg fields: Pair<String, Any?>): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive(type))
        for ((key, value) in fields) {
            when (value) {
                null -> {}
                is String -> put(key, JsonPrimitive(value))
                is Int -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is List<*> -> put(key, buildJsonArray {
                    value.filterIsInstance<String>().forEach { add(JsonPrimitive(it)) }
                })
                is JsonObject -> put(key, value)
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }

data class FileRef(val id: String?, val name: String, val bytes: Long?)

fun artifactFiles(artifacts: JsonObject?): List<FileRef> {
    val files = artifacts?.arr("files") ?: return emptyList()
    return files.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.str("name") ?: row.str("path")?.substringAfterLast('/') ?: return@mapNotNull null
        FileRef(row.str("id"), name, (row["bytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
    }
}
