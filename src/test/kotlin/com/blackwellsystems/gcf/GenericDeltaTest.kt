package com.blackwellsystems.gcf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Unit tests for generic-profile delta (SPEC Section 10a). Mirrors the other SDKs. */
class GenericDeltaTest {

    private fun ordersBase() = GenericSet(
        key = "id", name = "orders", fields = listOf("id", "total", "status", "customer"),
        rows = listOf(
            mapOf("id" to 1001, "total" to 59.98, "status" to "shipped", "customer" to "Alice"),
            mapOf("id" to 1002, "total" to 29.99, "status" to "pending", "customer" to "Bob"),
            mapOf("id" to 1003, "total" to 129.50, "status" to "shipped", "customer" to "Carol"),
        ),
    )

    private fun ordersNext() = GenericSet(
        key = "id", name = "orders", fields = listOf("id", "total", "status", "customer"),
        rows = listOf(
            mapOf("id" to 1002, "total" to 29.99, "status" to "shipped", "customer" to "Bob"),
            mapOf("id" to 1003, "total" to 129.50, "status" to "shipped", "customer" to "Carol"),
            mapOf("id" to 1004, "total" to 75.00, "status" to "pending", "customer" to "Dave"),
        ),
    )

    @Test
    fun roundTripByRoot() {
        val base = ordersBase(); val next = ordersNext()
        val d = diffGenericSets(base, next)
        assertEquals(1, d.added.size); assertEquals(1, d.changed.size); assertEquals(1, d.removed.size)
        assertEquals(genericPackRoot(next), d.newRoot)
        val result = verifyGenericDelta(base, d, genericPackRoot(next))
        assertEquals(genericPackRoot(next), genericPackRoot(result))
    }

    @Test
    fun packRootRowOrderInvariant() {
        val a = ordersBase()
        val b = a.copy(rows = listOf(a.rows[2], a.rows[0], a.rows[1]))
        assertEquals(genericPackRoot(a), genericPackRoot(b))
    }

    @Test
    fun canonicalCellNoCollision() {
        assertEquals("-", canonicalCell(null))
        assertEquals("true", canonicalCell(true))
        assertEquals("\"true\"", canonicalCell("true"))
        assertEquals("\"-\"", canonicalCell("-"))
        assertEquals("59.98", canonicalCell(59.98))
        assertEquals("\"59.98\"", canonicalCell("59.98"))
        assertEquals("\"a\\tb\"", canonicalCell("a\tb"))
    }

    @Test
    fun invariants() {
        val base = ordersBase()
        val baseRoot = genericPackRoot(base)

        val dup = base.copy(rows = base.rows + mapOf("id" to 1001, "total" to 1.0, "status" to "x", "customer" to "y"))
        assertTrue(assertFailsWith<IllegalArgumentException> { diffGenericSets(dup, ordersNext()) }
            .message!!.contains("duplicate identity"))

        val sc = ordersNext().copy(fields = listOf("id", "total", "status"))
        assertTrue(assertFailsWith<IllegalArgumentException> { diffGenericSets(base, sc) }
            .message!!.contains("schema change"))

        val addExisting = GenericDeltaPayload(key = "id", fields = base.fields, baseRoot = baseRoot,
            added = listOf(mapOf("id" to 1001, "total" to 1.0, "status" to "s", "customer" to "c")))
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyGenericDelta(base, addExisting, "sha256:x") }
            .message!!.contains("already exists"))

        val changeMissing = GenericDeltaPayload(key = "id", fields = base.fields, baseRoot = baseRoot,
            changed = listOf(mapOf("id" to 9999, "total" to 1.0, "status" to "s", "customer" to "c")))
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyGenericDelta(base, changeMissing, "sha256:x") }
            .message!!.contains("not in base"))

        val removeMissing = GenericDeltaPayload(key = "id", fields = base.fields, baseRoot = baseRoot,
            removed = listOf(9999))
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyGenericDelta(base, removeMissing, "sha256:x") }
            .message!!.contains("not in base"))

        val wrongBase = GenericDeltaPayload(key = "id", fields = base.fields, baseRoot = "sha256:wrong")
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyGenericDelta(base, wrongBase, baseRoot) }
            .message!!.contains("base_mismatch"))

        val d = diffGenericSets(base, ordersNext())
        assertTrue(assertFailsWith<IllegalArgumentException> { verifyGenericDelta(base, d, "sha256:deadbeef") }
            .message!!.contains("root_mismatch"))
    }

    @Test
    fun fullWireRoundTrip() {
        val base = ordersBase()
        val (got, pr) = decodeGenericFull(encodeGenericFull(base, "orders_query"))
        assertEquals(genericPackRoot(base), genericPackRoot(got))
        assertEquals(genericPackRoot(base), pr)
    }

    @Test
    fun endToEnd() {
        val base = ordersBase(); val next = ordersNext()
        val (held, _) = decodeGenericFull(encodeGenericFull(base, "orders_query"))
        val d = diffGenericSets(base, next)
        val parsed = decodeGenericDelta(encodeGenericDelta(d))
        val result = verifyGenericDelta(held, parsed, genericPackRoot(next))
        assertEquals(genericPackRoot(next), genericPackRoot(result))
    }

    @Test
    fun nullsAndStringKeys() {
        val nulls = GenericSet(key = "id", name = "items", fields = listOf("id", "total", "status", "customer"),
            rows = listOf(
                mapOf("id" to 2001, "total" to 10.0, "status" to null, "customer" to "Amy"),
                mapOf("id" to 2002, "total" to null, "status" to "open", "customer" to null),
            ))
        val (got, _) = decodeGenericFull(encodeGenericFull(nulls, ""))
        assertEquals(genericPackRoot(nulls), genericPackRoot(got))

        val sku = GenericSet(key = "sku", name = "parts", fields = listOf("sku", "name", "qty"),
            rows = listOf(
                mapOf("sku" to "1001", "name" to "Widget", "qty" to 5),
                mapOf("sku" to "A-200", "name" to "Gadget", "qty" to 3),
            ))
        val (got2, _) = decodeGenericFull(encodeGenericFull(sku, ""))
        assertEquals(genericPackRoot(sku), genericPackRoot(got2))
    }

    @Test
    fun decodeMalformedFailsClosed() {
        val cases = listOf(
            "",
            "GCF profile=graph delta=true base_root=a new_root=b key=id\n",
            "GCF profile=generic pack_root=r key=id\n## t [1]{@id}\n1\n",
            "GCF profile=generic delta=true base_root=a new_root=b key=id\n## added [2]{@id,x}\n1|2\n",
            "GCF profile=generic delta=true base_root=a new_root=b key=id\n## added [1]{@id,x}\n1\n",
            "GCF profile=generic delta=true base_root=a new_root=b key=id\n## bogus [1]{@id}\n1\n",
            "GCF profile=generic delta=true base_root=a new_root=b key=id\n## added [01]{@id,x}\n1|2\n",
        )
        for (wire in cases) {
            assertFailsWith<Exception>("expected error for ${wire.take(40)}") { decodeGenericDelta(wire) }
        }
    }
}
