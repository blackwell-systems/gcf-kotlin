package com.blackwellsystems.gcf

import kotlinx.serialization.json.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
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

    // Floor assertion: a green run MUST have exercised the full shared suite. The
    // factory below returns a single passing SKIP test when no fixtures are found, so a
    // mispathed or partial checkout would otherwise pass having verified almost nothing.
    // A wholly-absent sibling checkout is skipped (CI clones gcf in a separate step that
    // fails loudly if it cannot).
    @Test
    fun discoversFullSharedFixtureSet() {
        if (!fixtureDir.exists()) return
        val n = loadFixtures().size
        assertTrue(n >= 150, "discovered only $n conformance fixtures, expected at least 150; the shared gcf fixture set is incomplete or mispathed")
    }

    @TestFactory
    fun conformanceTests(): List<DynamicTest> {
        val fixtures = loadFixtures()
        if (fixtures.isEmpty()) return listOf(DynamicTest.dynamicTest("SKIP: fixtures not found") {})

        return fixtures.map { (relPath, data) ->
            DynamicTest.dynamicTest(relPath) {
                val op = data["operation"]?.jsonPrimitive?.content ?: return@dynamicTest
                if (data.containsKey("inputBase64")) return@dynamicTest
                if ("negative_zero" in relPath) return@dynamicTest
                // Skip a graph-stream-encode fixture requesting stream options this runner
                // does not support. labeledTrailerCounts (SPEC 8.4.1) IS supported; skip only
                // if options carries some OTHER key.
                if (op == "graph-stream-encode") {
                    val opts = (data["options"] as? JsonObject)
                    if (opts != null && opts.keys.any { it != "labeledTrailerCounts" }) return@dynamicTest
                }

                when (op) {
                    "encode" -> runEncode(relPath, data)
                    "decode" -> runDecode(relPath, data)
                    "roundtrip" -> runRoundtrip(relPath, data)
                    "error" -> runError(relPath, data)
                    "pack-root" -> runPackRoot(relPath, data)
                    "generic-pack-root" -> runGenericPackRoot(relPath, data)
                    "generic-delta" -> runGenericDelta(relPath, data)
                    "generic-delta-verify" -> runGenericDeltaApply(relPath, data, decode = false)
                    "generic-delta-decode" -> runGenericDeltaApply(relPath, data, decode = true)
                    "generic-delta-session" -> runGenericDeltaSession(relPath, data)
                    "graph-stream-encode" -> runGraphStreamEncode(relPath, data)
                    "session" -> runSession(relPath, data)
                    "delta" -> {
                        // graph-delta/002 is tagged `delta` but its `input` is a wire
                        // string (verify scenario with base_snapshot), not a DeltaPayload.
                        // Route it to the verify path rather than the encoder.
                        val inputIsWire = (data["input"] as? JsonPrimitive)?.isString == true
                        if (inputIsWire || data.containsKey("base_snapshot")) {
                            runDeltaVerify(relPath, data)
                        } else {
                            runDelta(relPath, data)
                        }
                    }
                    "delta-verify" -> runDeltaVerify(relPath, data)
                    else -> fail("unhandled operation: $op; must be handled or allow-listed")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runPackRoot(relPath: String, data: JsonObject) {
        val inp = jsonToAny(data["input"]!!) as Map<String, Any?>
        val symbols = (inp["symbols"] as? List<Any?> ?: emptyList()).map { s ->
            val m = s as Map<String, Any?>
            Symbol(
                qualifiedName = m["qualifiedName"] as? String ?: "",
                kind = m["kind"] as? String ?: "",
                score = (m["score"] as? Number)?.toDouble() ?: 0.0,
                provenance = m["provenance"] as? String ?: "",
                distance = (m["distance"] as? Number)?.toInt() ?: 0,
            )
        }
        val edges = (inp["edges"] as? List<Any?> ?: emptyList()).map { e ->
            val m = e as Map<String, Any?>
            Edge(
                source = m["source"] as? String ?: "",
                target = m["target"] as? String ?: "",
                edgeType = m["edgeType"] as? String ?: "",
                status = m["status"] as? String ?: "",
            )
        }
        assertEquals(data["expected"]!!.jsonPrimitive.content, packRoot(symbols, edges), "pack-root mismatch in $relPath")
    }

    private fun runGenericPackRoot(relPath: String, data: JsonObject) {
        val set = toSet(jsonToAny(data["input"]!!))
        assertEquals(data["expected"]!!.jsonPrimitive.content, genericPackRoot(set), "pack-root mismatch in $relPath")
    }

    private fun runGenericDelta(relPath: String, data: JsonObject) {
        val d = toDelta(jsonToAny(data["input"]!!))
        assertEquals(data["expected"]!!.jsonPrimitive.content, encodeGenericDelta(d), "delta encode mismatch in $relPath")
    }

    @Suppress("UNCHECKED_CAST")
    private fun runGenericDeltaApply(relPath: String, data: JsonObject, decode: Boolean) {
        val inp = jsonToAny(data["input"]!!) as Map<String, Any?>
        val base = toSet(inp["base"])
        val expNewRoot = inp["expectedNewRoot"] as? String ?: ""
        val expectedError = data["expectedError"]?.jsonPrimitive?.content
        val apply = {
            val d = if (decode) decodeGenericDelta(inp["wire"] as String) else toDelta(inp["delta"])
            verifyGenericDelta(base, d, expNewRoot)
        }
        if (expectedError != null) {
            try {
                apply()
                fail("expected error '$expectedError' but got success in $relPath")
            } catch (e: Exception) {
                assertTrue(expectedError in e.message.orEmpty(), "wrong error in $relPath: got '${e.message}'")
            }
        } else {
            assertEquals(data["expected"]!!.jsonPrimitive.content, genericPackRoot(apply()), "applied root mismatch in $relPath")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runGenericDeltaSession(relPath: String, data: JsonObject) {
        val inp = jsonToAny(data["input"]!!) as Map<String, Any?>
        val exp = jsonToAny(data["expected"]!!) as Map<String, Any?>
        val tool = inp["tool"] as? String ?: ""
        val policyMap = inp["policy"] as Map<String, Any?>
        val policy: ReanchorPolicy = if (policyMap["mode"] == "sizeGuard") {
            ReanchorPolicy.SizeGuard
        } else {
            ReanchorPolicy.FixedN((policyMap["n"] as? Number)?.toInt() ?: 0)
        }

        val session = GenericDeltaSession(toSet(inp["base"]), tool, policy)
        assertEquals(exp["initialFull"] as String, session.currentFull(), "initial full mismatch in $relPath")

        val updates = inp["updates"] as List<Any?>
        val emissions = exp["emissions"] as List<Any?>
        for ((i, up) in updates.withIndex()) {
            val (wire, isFull) = session.next(toSet(up))
            val emission = emissions[i] as Map<String, Any?>
            assertEquals(emission["isFull"] as Boolean, isFull, "turn ${i + 1} isFull mismatch in $relPath")
            assertEquals(emission["wire"] as String, wire, "turn ${i + 1} wire mismatch in $relPath")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runGraphStreamEncode(relPath: String, data: JsonObject) {
        val inp = jsonToAny(data["input"]!!) as Map<String, Any?>
        val tool = inp["tool"] as? String ?: ""
        val options = StreamOptions(
            tokenBudget = (inp["tokenBudget"] as? Number)?.toInt() ?: 0,
            tokensUsed = (inp["tokensUsed"] as? Number)?.toInt() ?: 0,
            packRoot = inp["packRoot"] as? String ?: "",
            labeledTrailerCounts = (data["options"] as? JsonObject)
                ?.get("labeledTrailerCounts")?.jsonPrimitive?.booleanOrNull ?: false,
        )

        val out = java.io.StringWriter()
        val enc = StreamEncoder(out, tool, options)

        for (s in (inp["symbols"] as? List<Any?> ?: emptyList())) {
            val m = s as Map<String, Any?>
            enc.writeSymbol(
                Symbol(
                    qualifiedName = m["qualifiedName"] as? String ?: "",
                    kind = m["kind"] as? String ?: "",
                    score = (m["score"] as? Number)?.toDouble() ?: 0.0,
                    provenance = m["provenance"] as? String ?: "",
                    distance = (m["distance"] as? Number)?.toInt() ?: 0,
                )
            )
        }
        for (e in (inp["edges"] as? List<Any?> ?: emptyList())) {
            val m = e as Map<String, Any?>
            enc.writeEdge(
                Edge(
                    source = m["source"] as? String ?: "",
                    target = m["target"] as? String ?: "",
                    edgeType = m["edgeType"] as? String ?: "",
                    status = m["status"] as? String ?: "",
                )
            )
        }
        enc.close()

        assertEquals(data["expected"]!!.jsonPrimitive.content, out.toString(), "graph-stream-encode mismatch in $relPath")
    }

    @Suppress("UNCHECKED_CAST")
    private fun toSet(v: Any?): GenericSet {
        val m = v as Map<String, Any?>
        return GenericSet(
            key = m["key"] as? String ?: "",
            fields = (m["fields"] as? List<Any?>)?.map { it as String } ?: emptyList(),
            rows = (m["rows"] as? List<Any?>)?.map { it as Map<String, Any?> } ?: emptyList(),
            name = m["name"] as? String ?: "",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toDelta(v: Any?): GenericDeltaPayload {
        val m = v as Map<String, Any?>
        return GenericDeltaPayload(
            key = m["key"] as? String ?: "",
            fields = (m["fields"] as? List<Any?>)?.map { it as String } ?: emptyList(),
            baseRoot = m["baseRoot"] as? String ?: "",
            newRoot = m["newRoot"] as? String ?: "",
            added = (m["added"] as? List<Any?>)?.map { it as Map<String, Any?> } ?: emptyList(),
            changed = (m["changed"] as? List<Any?>)?.map { it as Map<String, Any?> } ?: emptyList(),
            removed = (m["removed"] as? List<Any?>) ?: emptyList(),
            tool = m["tool"] as? String ?: "",
            deltaTokens = (m["deltaTokens"] as? Number)?.toInt() ?: 0,
            fullTokens = (m["fullTokens"] as? Number)?.toInt() ?: 0,
        )
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

        // Re-encode idempotence: encodeGeneric(decodeGeneric(got)) must equal `got`
        // byte-for-byte. structuralEqual ignores object key order, so it would pass a
        // decoder that reconstructs fields out of declared position; this order-sensitive
        // check catches that (SPEC key-order preservation, lines 52, 905, 931).
        assertReencodeIdempotent(relPath, got)
    }

    private fun runRoundtrip(relPath: String, data: JsonObject) {
        val input = jsonToAny(data["input"]!!)
        val got = encodeGeneric(input)
        data["expected"]?.jsonPrimitive?.content?.let { expected ->
            assertEquals(expected, got, "roundtrip encode mismatch in $relPath")
        }
        val decoded = decodeGeneric(got)
        assertTrue(structuralEqual(input, decoded), "round-trip mismatch in $relPath\n  got: $decoded\n  exp: $input")
        assertReencodeIdempotent(relPath, got)
    }

    // Assert that decoding then re-encoding a generic wire string is a byte-exact
    // fixed point. Graph wires are decoded through the graph path (a different value
    // shape) and are excluded here; this covers the generic profile only.
    private fun assertReencodeIdempotent(relPath: String, got: String) {
        if (got.startsWith("GCF profile=graph")) return
        val reencoded = encodeGeneric(decodeGeneric(got))
        assertEquals(got, reencoded, "re-encode idempotence mismatch in $relPath (decode reconstructed fields out of declared order)")
    }

    @Suppress("UNCHECKED_CAST")
    private fun runSession(relPath: String, data: JsonObject) {
        val calls = data["calls"]!!.jsonArray
        val session = Session()
        for ((i, callEl) in calls.withIndex()) {
            val call = callEl.jsonObject
            val inp = jsonToAny(call["input"]!!) as Map<String, Any?>
            val payload = Payload(
                tool = inp["tool"] as? String ?: "",
                symbols = (inp["symbols"] as? List<Any?> ?: emptyList()).map { s ->
                    val m = s as Map<String, Any?>
                    Symbol(
                        qualifiedName = m["qualifiedName"] as? String ?: "",
                        kind = m["kind"] as? String ?: "",
                        score = (m["score"] as? Number)?.toDouble() ?: 0.0,
                        provenance = m["provenance"] as? String ?: "",
                        distance = (m["distance"] as? Number)?.toInt() ?: 0,
                    )
                },
                edges = (inp["edges"] as? List<Any?> ?: emptyList()).map { e ->
                    val m = e as Map<String, Any?>
                    Edge(
                        source = m["source"] as? String ?: "",
                        target = m["target"] as? String ?: "",
                        edgeType = m["edgeType"] as? String ?: "",
                        status = m["status"] as? String ?: "",
                    )
                },
            )
            val expected = call["expected"]!!.jsonPrimitive.content
            assertEquals(expected, encodeWithSession(payload, session), "session call ${i + 1} mismatch in $relPath")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runDelta(relPath: String, data: JsonObject) {
        val inp = jsonToAny(data["input"]!!) as Map<String, Any?>
        fun symbols(key: String) = (inp[key] as? List<Any?> ?: emptyList()).map { s ->
            val m = s as Map<String, Any?>
            Symbol(
                qualifiedName = m["qualifiedName"] as? String ?: "",
                kind = m["kind"] as? String ?: "",
                score = (m["score"] as? Number)?.toDouble() ?: 0.0,
                provenance = m["provenance"] as? String ?: "",
                distance = (m["distance"] as? Number)?.toInt() ?: 0,
            )
        }
        fun edges(key: String) = (inp[key] as? List<Any?> ?: emptyList()).map { e ->
            val m = e as Map<String, Any?>
            Edge(
                source = m["source"] as? String ?: "",
                target = m["target"] as? String ?: "",
                edgeType = m["edgeType"] as? String ?: "",
                status = m["status"] as? String ?: "",
            )
        }
        val delta = DeltaPayload(
            tool = inp["tool"] as? String ?: "",
            baseRoot = inp["baseRoot"] as? String ?: "",
            newRoot = inp["newRoot"] as? String ?: "",
            removed = symbols("removed"),
            added = symbols("added"),
            removedEdges = edges("removedEdges"),
            addedEdges = edges("addedEdges"),
            deltaTokens = (inp["deltaTokens"] as? Number)?.toInt() ?: 0,
            fullTokens = (inp["fullTokens"] as? Number)?.toInt() ?: 0,
        )
        assertEquals(data["expected"]!!.jsonPrimitive.content, encodeDelta(delta), "delta encode mismatch in $relPath")
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotSymbols(v: Any?): List<Symbol> =
        (v as? List<Any?> ?: emptyList()).map { s ->
            val m = s as Map<String, Any?>
            Symbol(
                qualifiedName = m["qualifiedName"] as? String ?: "",
                kind = m["kind"] as? String ?: "",
                score = (m["score"] as? Number)?.toDouble() ?: 0.0,
                provenance = m["provenance"] as? String ?: "",
                distance = (m["distance"] as? Number)?.toInt() ?: 0,
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotEdges(v: Any?): List<Edge> =
        (v as? List<Any?> ?: emptyList()).map { e ->
            val m = e as Map<String, Any?>
            Edge(
                source = m["source"] as? String ?: "",
                target = m["target"] as? String ?: "",
                edgeType = m["edgeType"] as? String ?: "",
                status = m["status"] as? String ?: "",
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun runDeltaVerify(relPath: String, data: JsonObject) {
        val wire = data["input"]!!.jsonPrimitive.content
        val baseSnapshot = jsonToAny(data["base_snapshot"]!!) as Map<String, Any?>
        val baseSymbols = snapshotSymbols(baseSnapshot["symbols"])
        val baseEdges = snapshotEdges(baseSnapshot["edges"])
        val expectedError = data["expectedError"]?.jsonPrimitive?.content

        val d = decodeDelta(wire)
        val apply = {
            verifyDelta(
                baseSymbols, baseEdges,
                d.removed, d.added,
                d.removedEdges, d.addedEdges,
                d.newRoot,
            )
        }

        if (expectedError != null) {
            try {
                apply()
                fail("expected error '$expectedError' but got success in $relPath")
            } catch (e: Exception) {
                assertTrue(expectedError in e.message.orEmpty(), "wrong error in $relPath: got '${e.message}'")
            }
        } else {
            val (symbols, edges) = apply()
            val expectedSnapshot = jsonToAny(data["expected_snapshot"]!!) as Map<String, Any?>
            val expSymbols = snapshotSymbols(expectedSnapshot["symbols"])
            val expEdges = snapshotEdges(expectedSnapshot["edges"])
            assertEquals(packRoot(expSymbols, expEdges), packRoot(symbols, edges), "verified snapshot mismatch in $relPath")
        }
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
