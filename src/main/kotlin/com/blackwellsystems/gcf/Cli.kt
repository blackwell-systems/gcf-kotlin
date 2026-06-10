package com.blackwellsystems.gcf

import kotlinx.serialization.json.*

/**
 * CLI entry point for the GCF Kotlin library.
 *
 * Commands:
 *   encode-generic   Read JSON from stdin, encode to GCF generic profile, print to stdout
 *   decode-generic   Read GCF generic text from stdin, decode to JSON, print to stdout
 *   encode           Read JSON graph payload from stdin, encode to GCF graph profile, print to stdout
 *   decode           Read GCF graph text from stdin, decode to JSON, print to stdout
 *   version          Print library version
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        System.exit(1)
    }

    when (args[0]) {
        "encode-generic" -> cmdEncodeGeneric()
        "decode-generic" -> cmdDecodeGeneric()
        "encode" -> cmdEncode()
        "decode" -> cmdDecode()
        "version" -> println("gcf-kotlin 1.0.0")
        "-h", "--help", "help" -> printUsage()
        else -> {
            System.err.println("gcf: unknown command '${args[0]}'")
            printUsage()
            System.exit(1)
        }
    }
}

private fun printUsage() {
    System.err.println("""
        Usage: gcf <command>

        Commands:
          encode-generic   JSON from stdin -> GCF generic profile to stdout
          decode-generic   GCF generic text from stdin -> JSON to stdout
          encode           JSON graph payload from stdin -> GCF graph profile to stdout
          decode           GCF graph text from stdin -> JSON to stdout
          version          Print version
    """.trimIndent())
}

// --- encode-generic ---

private fun cmdEncodeGeneric() {
    val input = System.`in`.bufferedReader().readText()
    val element = Json.parseToJsonElement(input)
    val native = jsonElementToNative(element)
    print(encodeGeneric(native))
}

// --- decode-generic ---

private fun cmdDecodeGeneric() {
    val input = System.`in`.bufferedReader().readText()
    val result = decodeGeneric(input)
    val element = nativeToJsonElement(result)
    println(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element))
}

// --- encode (graph) ---

private fun cmdEncode() {
    val input = System.`in`.bufferedReader().readText()
    val json = Json.parseToJsonElement(input).jsonObject
    val payload = jsonToPayload(json)
    print(encode(payload))
}

// --- decode (graph) ---

private fun cmdDecode() {
    val input = System.`in`.bufferedReader().readText()
    val payload = decode(input)
    val element = payloadToJsonElement(payload)
    println(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element))
}

// --- JSON <-> native conversion ---

private fun jsonElementToNative(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> {
        if (element.isString) {
            element.content
        } else {
            element.booleanOrNull
                ?: element.longOrNull
                ?: element.doubleOrNull
                ?: element.content
        }
    }
    is JsonArray -> element.map { jsonElementToNative(it) }
    is JsonObject -> {
        val map = linkedMapOf<String, Any?>()
        for ((k, v) in element) {
            map[k] = jsonElementToNative(v)
        }
        map
    }
}

private fun nativeToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value.toDouble())
    is String -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map { nativeToJsonElement(it) })
    is Map<*, *> -> {
        val obj = buildJsonObject {
            for ((k, v) in value) {
                put(k.toString(), nativeToJsonElement(v))
            }
        }
        obj
    }
    else -> JsonPrimitive(value.toString())
}

// --- Payload JSON conversion ---

private fun jsonToPayload(obj: JsonObject): Payload {
    val symbols = (obj["symbols"] as? JsonArray)?.map { sym ->
        val s = sym.jsonObject
        Symbol(
            qualifiedName = s["qualifiedName"]?.jsonPrimitive?.content ?: "",
            kind = s["kind"]?.jsonPrimitive?.content ?: "",
            score = s["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            provenance = s["provenance"]?.jsonPrimitive?.content ?: "",
            distance = s["distance"]?.jsonPrimitive?.intOrNull ?: 0
        )
    } ?: emptyList()

    val edges = (obj["edges"] as? JsonArray)?.map { e ->
        val eo = e.jsonObject
        Edge(
            source = eo["source"]?.jsonPrimitive?.content ?: "",
            target = eo["target"]?.jsonPrimitive?.content ?: "",
            edgeType = eo["edgeType"]?.jsonPrimitive?.content ?: "",
            status = eo["status"]?.jsonPrimitive?.content ?: ""
        )
    } ?: emptyList()

    return Payload(
        tool = obj["tool"]?.jsonPrimitive?.content ?: "",
        tokenBudget = obj["tokenBudget"]?.jsonPrimitive?.intOrNull ?: 0,
        tokensUsed = obj["tokensUsed"]?.jsonPrimitive?.intOrNull ?: 0,
        packRoot = obj["packRoot"]?.jsonPrimitive?.content ?: "",
        symbols = symbols,
        edges = edges
    )
}

private fun payloadToJsonElement(p: Payload): JsonElement = buildJsonObject {
    put("tool", JsonPrimitive(p.tool))
    put("tokenBudget", JsonPrimitive(p.tokenBudget))
    put("tokensUsed", JsonPrimitive(p.tokensUsed))
    put("packRoot", JsonPrimitive(p.packRoot))
    put("symbols", JsonArray(p.symbols.map { s ->
        buildJsonObject {
            put("qualifiedName", JsonPrimitive(s.qualifiedName))
            put("kind", JsonPrimitive(s.kind))
            put("score", JsonPrimitive(s.score))
            put("provenance", JsonPrimitive(s.provenance))
            put("distance", JsonPrimitive(s.distance))
        }
    }))
    put("edges", JsonArray(p.edges.map { e ->
        buildJsonObject {
            put("source", JsonPrimitive(e.source))
            put("target", JsonPrimitive(e.target))
            put("edgeType", JsonPrimitive(e.edgeType))
            put("status", JsonPrimitive(e.status))
        }
    }))
}
