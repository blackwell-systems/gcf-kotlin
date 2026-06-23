package com.blackwellsystems.gcf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenericOptionsTest {

    @Test
    fun `noFlatten produces attachment syntax`() {
        val data = mapOf(
            "orders" to listOf(
                mapOf("id" to "ORD-1", "customer" to mapOf("name" to "Alice", "email" to "alice@co.com"), "total" to 99.99),
                mapOf("id" to "ORD-2", "customer" to mapOf("name" to "Bob", "email" to "bob@co.com"), "total" to 49.99),
            )
        )
        val withFlatten = encodeGeneric(data)
        assertTrue(withFlatten.contains("customer>"), "Expected path columns with default")

        val noFlatten = encodeGeneric(data, GenericOptions(noFlatten = true))
        assertFalse(noFlatten.contains("customer>"), "Expected no path columns with noFlatten")
        assertTrue(noFlatten.contains(".customer"), "Expected attachment syntax with noFlatten")
    }

    @Test
    fun `gt field edge cases round-trip`() {
        val cases = listOf<Pair<String, Any>>(
            "literal > key" to listOf(mapOf(">" to 1), mapOf(">" to 2)),
            "> at start" to listOf(mapOf(">foo" to "a", "id" to 1), mapOf(">foo" to "b", "id" to 2)),
            "> at end" to listOf(mapOf("foo>" to "a", "id" to 1), mapOf("foo>" to "b", "id" to 2)),
            "double >>" to listOf(mapOf("a>>b" to "x"), mapOf("a>>b" to "y")),
            "multiple > in key" to listOf(mapOf("a>b>c" to "x"), mapOf("a>b>c" to "y")),
            "> field with null" to listOf(mapOf("a>b" to null, "id" to 1), mapOf<String, Any?>("a>b" to "hello", "id" to 2)),
            "> field with object" to listOf(mapOf("a>b" to mapOf("x" to 1), "id" to 1), mapOf("a>b" to mapOf("x" to 2), "id" to 2)),
            "> field with array" to listOf(mapOf("a>b" to listOf(1, 2), "id" to 1), mapOf("a>b" to listOf(3), "id" to 2)),
            "all fields have >" to listOf(mapOf(">" to 1, "a>b" to 2), mapOf(">" to 3, "a>b" to 4)),
            "key looks like flattened path" to listOf(mapOf("id" to 1, "customer>name" to "Alice"), mapOf("id" to 2, "customer>name" to "Bob")),
        )

        for ((name, data) in cases) {
            for (noFlatten in listOf(false, true)) {
                val encoded = encodeGeneric(data, GenericOptions(noFlatten = noFlatten))
                val decoded = decodeGeneric(encoded)
                assertEquals(
                    normalizeJson(data),
                    normalizeJson(decoded),
                    "$name (noFlatten=$noFlatten) round-trip mismatch\n  gcf: $encoded"
                )
            }
        }
    }

    /** Normalize for comparison: sort map keys recursively, convert to string. */
    @Suppress("UNCHECKED_CAST")
    private fun normalizeJson(v: Any?): String {
        return when (v) {
            null -> "null"
            is Map<*, *> -> {
                val sorted = (v as Map<String, Any?>).toSortedMap()
                "{" + sorted.entries.joinToString(",") { "\"${it.key}\":${normalizeJson(it.value)}" } + "}"
            }
            is List<*> -> "[" + v.joinToString(",") { normalizeJson(it) } + "]"
            is String -> "\"$v\""
            is Number -> {
                val d = v.toDouble()
                if (d == d.toLong().toDouble() && d < 1e15) d.toLong().toString() else d.toString()
            }
            is Boolean -> v.toString()
            else -> v.toString()
        }
    }
}
