package com.blackwellsystems.gcf

import kotlinx.serialization.json.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ConformanceV2Test {

    private val fixtureDir = File(System.getProperty("user.dir"))
        .resolve("../gcf/tests/conformance")

    data class Fixture(val relPath: String, val data: JsonObject)

    private fun loadFixtures(): List<Fixture> {
        if (!fixtureDir.exists()) return emptyList()
        return fixtureDir.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { Fixture(it.relativeTo(fixtureDir).path, Json.parseToJsonElement(it.readText()).jsonObject) }
            .sortedBy { it.relPath }
            .toList()
    }

    @TestFactory
    fun conformanceTests(): List<DynamicTest> {
        val fixtures = loadFixtures()
        if (fixtures.isEmpty()) return listOf(DynamicTest.dynamicTest("SKIP: fixtures not found") {})

        return fixtures.map { (relPath, data) ->
            DynamicTest.dynamicTest(relPath) {
                val op = data["operation"]?.jsonPrimitive?.content ?: return@dynamicTest
                if (op in listOf("session", "delta")) return@dynamicTest
                if (data.containsKey("inputBase64")) return@dynamicTest
                if ("negative_zero" in relPath) return@dynamicTest

                when (op) {
                    "encode" -> runEncode(relPath, data)
                    "decode" -> runDecode(relPath, data)
                    "error" -> runError(relPath, data)
                }
            }
        }
    }

    private fun runEncode(relPath: String, data: JsonObject) {
        val expected = data["expected"]?.jsonPrimitive?.content ?: return
        if (expected.startsWith("GCF profile=graph")) return // skip graph encode

        val input = jsonToAny(data["input"]!!)
        val got = encodeGeneric(input)
        assertEquals(expected, got, "encode mismatch in $relPath")

        // Round-trip.
        val decoded = decodeGeneric(got)
        assertTrue(structuralEqual(jsonToAny(data["input"]!!), decoded), "round-trip mismatch in $relPath")
    }

    private fun runDecode(relPath: String, data: JsonObject) {
        val inputStr = data["input"]?.jsonPrimitive?.content ?: return
        val got = decodeGeneric(inputStr)
        val expected = jsonToAny(data["expected"]!!)
        assertTrue(subsetMatch(expected, got), "decode mismatch in $relPath\n  got: $got\n  exp: $expected")
    }

    private fun runError(relPath: String, data: JsonObject) {
        val inputStr = data["input"]?.jsonPrimitive?.content ?: return
        val expectedError = data["expectedError"]?.jsonPrimitive?.content ?: return
        try {
            decodeGeneric(inputStr)
            fail("expected error '$expectedError' but got success in $relPath")
        } catch (e: Exception) {
            assertTrue(expectedError in e.message.orEmpty(),
                "wrong error in $relPath: got '${e.message}', expected '$expectedError'")
        }
    }

    private fun jsonToAny(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.intOrNull != null -> element.int
            element.longOrNull != null -> element.long
            element.doubleOrNull != null -> element.double
            else -> element.content
        }
        is JsonArray -> element.map { jsonToAny(it) }
        is JsonObject -> linkedMapOf<String, Any?>().also { map ->
            for ((k, v) in element) map[k] = jsonToAny(v)
        }
    }

    private fun structuralEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Map<*, *> && b is Map<*, *>) {
            val am = a as Map<String, Any?>
            val bm = b as Map<String, Any?>
            if (am.keys.toSortedSet() != bm.keys.toSortedSet()) return false
            return am.keys.all { structuralEqual(am[it], bm[it]) }
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            return a.zip(b).all { (x, y) -> structuralEqual(x, y) }
        }
        return a == b
    }

    private fun subsetMatch(expected: Any?, got: Any?): Boolean {
        if (expected is Map<*, *> && got is Map<*, *>) {
            val em = expected as Map<String, Any?>
            val gm = got as Map<String, Any?>
            return em.keys.all { k -> k in gm && subsetMatch(em[k], gm[k]) }
        }
        if (expected is List<*> && got is List<*>) {
            if (expected.size != got.size) return false
            return expected.zip(got).all { (e, g) -> subsetMatch(e, g) }
        }
        if (expected is Number && got is Number) return expected.toDouble() == got.toDouble()
        return expected == got
    }
}
