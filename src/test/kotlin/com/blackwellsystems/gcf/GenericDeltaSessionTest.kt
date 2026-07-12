package com.blackwellsystems.gcf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Mirrors gcf-go/generic_delta_session_test.go: verifies the producer-side
// re-anchor cadence helper (SPEC Section 10a.8).

class GenericDeltaSessionTest {

    // --- scenario builders ---

    private fun sessBase(): GenericSet = GenericSet(
        name = "orders", key = "id", fields = listOf("id", "total", "status", "customer"),
        rows = listOf(
            mapOf("id" to 1001.0, "total" to 59.98, "status" to "shipped", "customer" to "Alice"),
            mapOf("id" to 1002.0, "total" to 29.99, "status" to "pending", "customer" to "Bob"),
            mapOf("id" to 1003.0, "total" to 129.50, "status" to "shipped", "customer" to "Carol"),
        ),
    )

    private fun mkOrders(vararg rows: Map<String, Any?>): GenericSet = GenericSet(
        name = "orders", key = "id", fields = listOf("id", "total", "status", "customer"),
        rows = rows.toList(),
    )

    private fun sessUpdates(): List<GenericSet> = listOf(
        mkOrders(
            mapOf("id" to 1001.0, "total" to 59.98, "status" to "shipped", "customer" to "Alice"),
            mapOf("id" to 1002.0, "total" to 29.99, "status" to "shipped", "customer" to "Bob"), // changed
            mapOf("id" to 1003.0, "total" to 129.50, "status" to "shipped", "customer" to "Carol"),
        ),
        mkOrders( // add 1004
            mapOf("id" to 1001.0, "total" to 59.98, "status" to "shipped", "customer" to "Alice"),
            mapOf("id" to 1002.0, "total" to 29.99, "status" to "shipped", "customer" to "Bob"),
            mapOf("id" to 1003.0, "total" to 129.50, "status" to "shipped", "customer" to "Carol"),
            mapOf("id" to 1004.0, "total" to 75.00, "status" to "pending", "customer" to "Dave"),
        ),
        mkOrders( // remove 1001
            mapOf("id" to 1002.0, "total" to 29.99, "status" to "shipped", "customer" to "Bob"),
            mapOf("id" to 1003.0, "total" to 129.50, "status" to "shipped", "customer" to "Carol"),
            mapOf("id" to 1004.0, "total" to 75.00, "status" to "pending", "customer" to "Dave"),
        ),
        mkOrders( // change 1003
            mapOf("id" to 1002.0, "total" to 29.99, "status" to "shipped", "customer" to "Bob"),
            mapOf("id" to 1003.0, "total" to 140.00, "status" to "delivered", "customer" to "Carol"),
            mapOf("id" to 1004.0, "total" to 75.00, "status" to "pending", "customer" to "Dave"),
        ),
        mkOrders( // add 1005
            mapOf("id" to 1002.0, "total" to 29.99, "status" to "shipped", "customer" to "Bob"),
            mapOf("id" to 1003.0, "total" to 140.00, "status" to "delivered", "customer" to "Carol"),
            mapOf("id" to 1004.0, "total" to 75.00, "status" to "pending", "customer" to "Dave"),
            mapOf("id" to 1005.0, "total" to 12.00, "status" to "pending", "customer" to "Eve"),
        ),
    )

    // larger base + one-row updates so SizeGuard's cumulative delta reaches a full.
    private fun sizeGuardBase(): GenericSet {
        val names = listOf(
            "Alice", "Bob", "Carol", "Dave", "Eve", "Frank", "Grace", "Heidi",
            "Ivan", "Judy", "Mallory", "Niaj", "Olivia", "Peggy", "Rupert", "Sybil",
            "Trent", "Uma", "Victor", "Walter",
        )
        val rows = names.mapIndexed { i, n ->
            mapOf<String, Any?>("id" to (2000 + i).toDouble(), "total" to (10 + i).toDouble(), "status" to "pending", "customer" to n)
        }
        return GenericSet(name = "rows", key = "id", fields = listOf("id", "total", "status", "customer"), rows = rows)
    }

    private fun sizeGuardUpdates(): List<GenericSet> {
        val base = sizeGuardBase()
        val ups = mutableListOf<GenericSet>()
        for (turn in 0 until 6) {
            val rows = base.rows.mapIndexed { i, r ->
                val nr = LinkedHashMap(r)
                if (i == turn) nr["status"] = "shipped"
                nr
            }
            ups.add(GenericSet(name = base.name, key = base.key, fields = base.fields, rows = rows))
        }
        return ups
    }

    // --- unit tests ---

    @Test
    fun sessionFixedNPattern() {
        val s = GenericDeltaSession(sessBase(), "orders_query", ReanchorPolicy.FixedN(3))
        val wantFull = listOf(false, false, true, false, false) // re-anchor on turn 3
        for ((i, up) in sessUpdates().withIndex()) {
            val (_, isFull) = s.next(up)
            assertEquals(wantFull[i], isFull, "turn ${i + 1}")
        }
    }

    @Test
    fun sessionSizeGuardTriggers() {
        val s = GenericDeltaSession(sizeGuardBase(), "", ReanchorPolicy.SizeGuard)
        var anchors = 0
        for (up in sizeGuardUpdates()) {
            val (_, isFull) = s.next(up)
            if (isFull) anchors++
        }
        assertTrue(anchors >= 1, "SizeGuard never re-anchored across 6 turns; scenario should trigger at least one")
    }

    @Test
    fun sessionSchemaChangeReanchors() {
        val s = GenericDeltaSession(sessBase(), "orders_query", ReanchorPolicy.FixedN(15))
        val changed = GenericSet(
            name = "orders", key = "id", fields = listOf("id", "total", "status"), // drop a column
            rows = listOf(mapOf("id" to 1001.0, "total" to 59.98, "status" to "shipped")),
        )
        val (_, isFull) = s.next(changed)
        assertTrue(isFull, "schema change must force a full re-anchor")
    }

    // With N=15 over 30 update turns, exactly two emissions are full re-anchors
    // (turns 15 and 30); the other 28 are deltas.
    @Test
    fun sessionFixedN15Over30Turns() {
        val s = GenericDeltaSession(sessBase(), "orders_query", ReanchorPolicy.FixedN(15))
        s.currentFull() // bootstrap full (turn 0), not counted below

        var fulls = 0
        var deltas = 0
        val fullTurns = mutableListOf<Int>()
        var prev = sessBase()
        for (turn in 1..30) {
            // mutate one row's total each turn so every turn is a real, same-schema delta
            val rows = prev.rows.mapIndexed { j, r ->
                val nr = LinkedHashMap(r)
                if (j == turn % prev.rows.size) nr["total"] = turn.toDouble() + 0.5
                nr
            }
            val next = GenericSet(name = prev.name, key = prev.key, fields = prev.fields, rows = rows)
            val (_, isFull) = s.next(next)
            if (isFull) {
                fulls++
                fullTurns.add(turn)
            } else {
                deltas++
            }
            prev = next
        }
        assertEquals(2, fulls, "over 30 turns: fulls")
        assertEquals(28, deltas, "over 30 turns: deltas")
        assertEquals(listOf(15, 30), fullTurns, "full re-anchor turns")
    }

    // The load-bearing test: a consumer that applies each emission (full -> decode,
    // delta -> decode+verify) stays byte-for-byte in sync with the producer's state
    // at every turn, under both policies.
    @Test
    fun sessionConsumerStaysInSync() {
        data class Case(val name: String, val base: GenericSet, val ups: List<GenericSet>, val tool: String, val policy: ReanchorPolicy)
        val cases = listOf(
            Case("fixedN3", sessBase(), sessUpdates(), "orders_query", ReanchorPolicy.FixedN(3)),
            Case("sizeGuard", sizeGuardBase(), sizeGuardUpdates(), "", ReanchorPolicy.SizeGuard),
        )
        for (tc in cases) {
            val s = GenericDeltaSession(tc.base, tc.tool, tc.policy)
            var held = decodeGenericFull(s.currentFull()).first
            for ((i, up) in tc.ups.withIndex()) {
                val (wire, isFull) = s.next(up)
                held = if (isFull) {
                    decodeGenericFull(wire).first
                } else {
                    val d = decodeGenericDelta(wire)
                    verifyGenericDelta(held, d, d.newRoot)
                }
                assertEquals(genericPackRoot(up), genericPackRoot(held), "${tc.name} turn ${i + 1}: consumer root != producer root (isFull=$isFull)")
            }
        }
    }
}
