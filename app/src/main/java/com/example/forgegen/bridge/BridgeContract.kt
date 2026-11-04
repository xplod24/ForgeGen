package com.example.forgegen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

/** Validate required wire shapes before a screen consumes untrusted data; tolerate additive fields and enums. */
internal object BridgeContract {
    private val routes by lazy {
        BridgeWireSchemas.routes.map { it.asJsonObject }.map { route ->
            val pattern =
                route.text("path").removePrefix("/forgegen/v1/").split('/').joinToString("/") {
                    if (it.startsWith('{') && it.endsWith('}')) "[^/]+" else Regex.escape(it)
                }
            Regex(pattern) to route
        }
    }

    fun response(
        method: String,
        path: String,
        status: Int,
        value: JsonObject,
    ) {
        try {
            val route = routes.first { it.second.text("method") == method && it.first.matches(path) }.second
            require(route.get("status").asInt == status)
            val schema = route.text("response")
            if (schema.isNotEmpty()) validate(value, BridgeWireSchemas.schemas.getAsJsonObject(schema), 0)
        } catch (_: Exception) {
            throw BridgeFailure(status, "INVALID_RESPONSE", method !in setOf("GET", "OPTIONS"))
        }
    }

    private fun validate(
        value: JsonElement,
        original: JsonObject,
        depth: Int,
    ) {
        require(depth < 24)
        val schema =
            original.get("\$ref")?.asString?.let { BridgeWireSchemas.schemas.getAsJsonObject(it.substringAfterLast('/')) } ?: original
        schema.getAsJsonArray("anyOf")?.let { choices ->
            require(choices.any { runCatching { validate(value, it.asJsonObject, depth + 1) }.isSuccess })
            return
        }
        when (schema.text("type")) {
            "null" -> require(value.isJsonNull)
            "object" -> {
                require(value.isJsonObject)
                val obj = value.asJsonObject
                schema.getAsJsonArray("required")?.forEach { require(obj.has(it.asString)) }
                if (schema.text("title") in setOf("Model", "Extension", "Device", "Operation")) BridgeProtocol.id(obj.text("id"))
                val properties = schema.getAsJsonObject("properties")
                obj.entrySet().forEach { (key, item) ->
                    val property = properties?.get(key)?.asJsonObject
                    val additional = schema.get("additionalProperties")?.takeIf { it.isJsonObject }?.asJsonObject
                    (property ?: additional)?.let { validate(item, it, depth + 1) }
                }
            }
            "array" -> {
                require(value.isJsonArray)
                schema.getAsJsonObject("items")?.let { item -> value.asJsonArray.forEach { validate(it, item, depth + 1) } }
            }
            "string" -> {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                val text = value.asString
                require(text.length >= (schema.get("minLength")?.asInt ?: 0))
                require(text.length <= (schema.get("maxLength")?.asInt ?: Int.MAX_VALUE))
                when (schema.text("format")) {
                    "uuid" -> require(BridgeProtocol.uuid(text))
                    "date-time" -> Instant.parse(text)
                }
            }
            "boolean" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            "integer", "number" -> {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asDouble.isFinite())
                if (schema.text("type") == "integer") require(value.asBigDecimal.stripTrailingZeros().scale() <= 0)
            }
        }
    }
}
