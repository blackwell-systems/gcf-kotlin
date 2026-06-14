package com.blackwellsystems.gcf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GcfTest {

    // -- Encode --

    @Test
    fun `encode basic payload`() {
        val p = Payload(
            tool = "context_for_task",
            tokenBudget = 5000,
            tokensUsed = 1847,
            symbols = listOf(
                Symbol(qualifiedName = "pkg.AuthMiddleware", kind = "function", score = 0.78, provenance = "lsp_resolved", distance = 0),
                Symbol(qualifiedName = "pkg.NewServer", kind = "function", score = 0.54, provenance = "lsp_resolved", distance = 1),
            ),
            edges = listOf(
                Edge(source = "pkg.NewServer", target = "pkg.AuthMiddleware", edgeType = "calls")
            )
        )
        val output = encode(p)
        assertContains(output, "GCF profile=graph tool=context_for_task budget=5000 tokens=1847 symbols=2")
        assertContains(output, "## targets")
        assertContains(output, "@0 fn pkg.AuthMiddleware 0.78 lsp_resolved")
        assertContains(output, "## related")
        assertContains(output, "@1 fn pkg.NewServer 0.54 lsp_resolved")
        assertContains(output, "## edges")
        assertContains(output, "@0<@1 calls")
    }

    @Test
    fun `encode with pack root`() {
        val p = Payload(
            tool = "test",
            packRoot = "abc123",
            symbols = listOf(
                Symbol(qualifiedName = "X", kind = "type", score = 0.50, provenance = "ast", distance = 0)
            )
        )
        val output = encode(p)
        assertContains(output, "pack_root=abc123")
    }

    @Test
    fun `encode score formatting`() {
        val p = Payload(
            tool = "test",
            symbols = listOf(
                Symbol(qualifiedName = "A", kind = "function", score = 0.9, provenance = "x", distance = 0),
                Symbol(qualifiedName = "B", kind = "function", score = 1.0, provenance = "x", distance = 0),
            )
        )
        val output = encode(p)
        assertContains(output, "0.90")
        assertContains(output, "1.00")
    }

    @Test
    fun `encode edge with status`() {
        val p = Payload(
            tool = "test",
            symbols = listOf(
                Symbol(qualifiedName = "A", kind = "function", score = 0.5, provenance = "x", distance = 0),
                Symbol(qualifiedName = "B", kind = "function", score = 0.5, provenance = "x", distance = 0),
            ),
            edges = listOf(
                Edge(source = "A", target = "B", edgeType = "calls", status = "added")
            )
        )
        val output = encode(p)
        assertContains(output, "@1<@0 calls added")
    }

    @Test
    fun `encode skips unchanged status`() {
        val p = Payload(
            tool = "test",
            symbols = listOf(
                Symbol(qualifiedName = "A", kind = "function", score = 0.5, provenance = "x", distance = 0),
                Symbol(qualifiedName = "B", kind = "function", score = 0.5, provenance = "x", distance = 0),
            ),
            edges = listOf(
                Edge(source = "A", target = "B", edgeType = "calls", status = "unchanged")
            )
        )
        val output = encode(p)
        assertTrue(output.contains("@1<@0 calls\n"))
    }

    @Test
    fun `encode distance groups`() {
        val p = Payload(
            tool = "test",
            symbols = listOf(
                Symbol(qualifiedName = "A", kind = "function", score = 0.9, provenance = "x", distance = 0),
                Symbol(qualifiedName = "B", kind = "function", score = 0.7, provenance = "x", distance = 1),
                Symbol(qualifiedName = "C", kind = "function", score = 0.3, provenance = "x", distance = 2),
                Symbol(qualifiedName = "D", kind = "function", score = 0.1, provenance = "x", distance = 5),
            )
        )
        val output = encode(p)
        assertContains(output, "## targets")
        assertContains(output, "## related")
        assertContains(output, "## extended")
        assertContains(output, "## distance_5")
    }

    // -- Decode --

    @Test
    fun `decode basic payload`() {
        val input = """
            GCF profile=graph tool=context_for_task budget=5000 tokens=1847 symbols=2
            ## targets
            @0 fn pkg.AuthMiddleware 0.78 lsp_resolved
            ## related
            @1 fn pkg.NewServer 0.54 lsp_resolved
            ## edges
            @0<@1 calls
        """.trimIndent()

        val p = decode(input)
        assertEquals("context_for_task", p.tool)
        assertEquals(5000, p.tokenBudget)
        assertEquals(1847, p.tokensUsed)
        assertEquals(2, p.symbols.size)
        assertEquals("pkg.AuthMiddleware", p.symbols[0].qualifiedName)
        assertEquals("function", p.symbols[0].kind) // expanded from "fn"
        assertEquals(0.78, p.symbols[0].score)
        assertEquals(0, p.symbols[0].distance)
        assertEquals("pkg.NewServer", p.symbols[1].qualifiedName)
        assertEquals(1, p.symbols[1].distance)
        assertEquals(1, p.edges.size)
        assertEquals("pkg.NewServer", p.edges[0].source)
        assertEquals("pkg.AuthMiddleware", p.edges[0].target)
        assertEquals("calls", p.edges[0].edgeType)
    }

    @Test
    fun `decode rejects missing GCF prefix`() {
        val ex = assertThrows<DecodeException> { decode("INVALID header") }
        assertContains(ex.message!!, "invalid header")
    }

    @Test
    fun `decode accepts missing tool`() {
        // v3.1: tool field is optional.
        val result = decode("GCF profile=graph budget=100 tokens=50 symbols=0")
        assertEquals("", result.tool)
    }

    @Test
    fun `decode rejects less than 5 fields in symbol line`() {
        val input = """
            GCF profile=graph tool=test budget=0 tokens=0 symbols=1
            ## targets
            @0 fn pkg.Func 0.5
        """.trimIndent()
        val ex = assertThrows<DecodeException> { decode(input) }
        assertContains(ex.message!!, "at least 5 fields")
    }

    @Test
    fun `decode rejects unknown edge IDs`() {
        val input = """
            GCF profile=graph tool=test budget=0 tokens=0 symbols=1
            ## targets
            @0 fn pkg.Func 0.5 lsp
            ## edges
            @0<@99 calls
        """.trimIndent()
        val ex = assertThrows<DecodeException> { decode(input) }
        assertContains(ex.message!!, "unknown symbol id")
    }

    @Test
    fun `decode expands kind abbreviations`() {
        val input = """
            GCF profile=graph tool=test budget=0 tokens=0 symbols=3
            ## targets
            @0 iface pkg.Handler 0.9 lsp
            @1 ext pkg.Library 0.8 ast
            @2 svc pkg.Auth 0.7 lsp
        """.trimIndent()

        val p = decode(input)
        assertEquals("interface", p.symbols[0].kind)
        assertEquals("external", p.symbols[1].kind)
        assertEquals("service", p.symbols[2].kind)
    }

    @Test
    fun `decode tolerates carriage return`() {
        val input = "GCF profile=graph tool=test budget=0 tokens=0 symbols=1\r\n## targets\r\n@0 fn pkg.Func 0.50 lsp\r\n"
        val p = decode(input)
        assertEquals(1, p.symbols.size)
        assertEquals("pkg.Func", p.symbols[0].qualifiedName)
    }

    @Test
    fun `decode with pack root`() {
        val input = "GCF profile=graph tool=test budget=0 tokens=0 symbols=0 pack_root=deadbeef\n"
        val p = decode(input)
        assertEquals("deadbeef", p.packRoot)
    }

    // -- Roundtrip --

    @Test
    fun `roundtrip encode then decode`() {
        val original = Payload(
            tool = "roundtrip_test",
            tokenBudget = 3000,
            tokensUsed = 500,
            symbols = listOf(
                Symbol(qualifiedName = "a.Foo", kind = "function", score = 0.95, provenance = "lsp_resolved", distance = 0),
                Symbol(qualifiedName = "a.Bar", kind = "method", score = 0.80, provenance = "ast_inferred", distance = 1),
            ),
            edges = listOf(
                Edge(source = "a.Foo", target = "a.Bar", edgeType = "calls")
            )
        )
        val encoded = encode(original)
        val decoded = decode(encoded)

        assertEquals(original.tool, decoded.tool)
        assertEquals(original.tokenBudget, decoded.tokenBudget)
        assertEquals(original.tokensUsed, decoded.tokensUsed)
        assertEquals(original.symbols.size, decoded.symbols.size)
        for (i in original.symbols.indices) {
            assertEquals(original.symbols[i].qualifiedName, decoded.symbols[i].qualifiedName)
            assertEquals(original.symbols[i].kind, decoded.symbols[i].kind)
            assertEquals(original.symbols[i].score, decoded.symbols[i].score, 0.005)
            assertEquals(original.symbols[i].provenance, decoded.symbols[i].provenance)
            assertEquals(original.symbols[i].distance, decoded.symbols[i].distance)
        }
        assertEquals(original.edges.size, decoded.edges.size)
        assertEquals(original.edges[0].source, decoded.edges[0].source)
        assertEquals(original.edges[0].target, decoded.edges[0].target)
        assertEquals(original.edges[0].edgeType, decoded.edges[0].edgeType)
    }

    // -- Session --

    @Test
    fun `session deduplication`() {
        val session = Session()
        val p1 = Payload(
            tool = "test",
            tokenBudget = 1000,
            tokensUsed = 100,
            symbols = listOf(
                Symbol(qualifiedName = "pkg.Func1", kind = "function", score = 0.9, provenance = "lsp", distance = 0),
            )
        )
        val out1 = encodeWithSession(p1, session)
        assertContains(out1, "session=true")
        assertContains(out1, "fn pkg.Func1 0.90 lsp")
        assertEquals(1, session.size())

        // Second call: Func1 should be bare ref, Func2 full.
        val p2 = Payload(
            tool = "test",
            tokenBudget = 1000,
            tokensUsed = 50,
            symbols = listOf(
                Symbol(qualifiedName = "pkg.Func1", kind = "function", score = 0.9, provenance = "lsp", distance = 0),
                Symbol(qualifiedName = "pkg.Func2", kind = "function", score = 0.7, provenance = "lsp", distance = 0),
            )
        )
        val out2 = encodeWithSession(p2, session)
        assertContains(out2, "@0  # previously transmitted")
        assertContains(out2, "fn pkg.Func2 0.70 lsp")
        assertEquals(2, session.size())
    }

    @Test
    fun `session null falls back to regular encode`() {
        val p = Payload(
            tool = "test",
            symbols = listOf(
                Symbol(qualifiedName = "X", kind = "type", score = 0.5, provenance = "x", distance = 0)
            )
        )
        val out = encodeWithSession(p, null)
        assertTrue(!out.contains("session=true"))
    }

    @Test
    fun `session reset`() {
        val session = Session()
        session.record(listOf(Symbol(qualifiedName = "A", kind = "type", score = 0.5, provenance = "x")))
        assertEquals(1, session.size())
        session.reset()
        assertEquals(0, session.size())
        assertEquals(-1, session.getID("A"))
    }

    @Test
    fun `session thread safety`() {
        val session = Session()
        val threads = (0 until 10).map { t ->
            Thread {
                val symbols = (0 until 100).map { i ->
                    Symbol(qualifiedName = "thread${t}_sym$i", kind = "function", score = 0.5, provenance = "x")
                }
                session.record(symbols)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1000, session.size())
    }

    // -- Delta --

    @Test
    fun `encode delta`() {
        val delta = DeltaPayload(
            tool = "context_for_task",
            baseRoot = "aaa111",
            newRoot = "bbb222",
            removed = listOf(
                Symbol(qualifiedName = "pkg.OldFunc", kind = "function")
            ),
            added = listOf(
                Symbol(qualifiedName = "pkg.NewFunc", kind = "function", score = 0.85, provenance = "rwr")
            ),
            deltaTokens = 30,
            fullTokens = 200
        )
        val output = encodeDelta(delta)
        assertContains(output, "GCF profile=graph tool=context_for_task delta=true base_root=aaa111 new_root=bbb222 tokens=30 savings=85%")
        assertContains(output, "## removed")
        assertContains(output, "fn pkg.OldFunc")
        assertContains(output, "## added")
        assertContains(output, "@0 fn pkg.NewFunc 0.85 rwr")
    }

    @Test
    fun `encode delta with edges`() {
        val delta = DeltaPayload(
            tool = "test",
            baseRoot = "a",
            newRoot = "b",
            removedEdges = listOf(
                Edge(source = "A", target = "B", edgeType = "calls")
            ),
            addedEdges = listOf(
                Edge(source = "C", target = "D", edgeType = "imports")
            ),
            deltaTokens = 10,
            fullTokens = 100
        )
        val output = encodeDelta(delta)
        assertContains(output, "## edges_removed")
        assertContains(output, "A -> B calls")
        assertContains(output, "## edges_added")
        assertContains(output, "C -> D imports")
    }

    @Test
    fun `encode delta zero full tokens`() {
        val delta = DeltaPayload(
            tool = "test",
            baseRoot = "a",
            newRoot = "b",
            deltaTokens = 0,
            fullTokens = 0
        )
        val output = encodeDelta(delta)
        assertContains(output, "savings=0%")
    }

    // -- Generic Encoding --

    @Test
    fun `generic tabular`() {
        val data = mapOf(
            "employees" to listOf(
                mapOf("id" to 1, "name" to "Alice", "department" to "Engineering", "salary" to 95000),
                mapOf("id" to 2, "name" to "Bob", "department" to "Sales", "salary" to 72000),
            )
        )
        val output = encodeGeneric(data)
        assertContains(output, "## employees [2]{id,name,department,salary}")
        assertContains(output, "1|Alice|Engineering|95000")
        assertContains(output, "2|Bob|Sales|72000")
    }

    @Test
    fun `generic primitive`() {
        assertEquals("GCF profile=generic\n=42\n", encodeGeneric(42))
        assertEquals("GCF profile=generic\n=3.14\n", encodeGeneric(3.14))
        assertEquals("GCF profile=generic\n=true\n", encodeGeneric(true))
        assertEquals("GCF profile=generic\n=hello\n", encodeGeneric("hello"))
    }

    @Test
    fun `generic null`() {
        assertEquals("GCF profile=generic\n=-\n", encodeGeneric(null))
    }

    @Test
    fun `generic nested object`() {
        val data = mapOf(
            "name" to "test",
            "config" to mapOf(
                "debug" to true,
                "level" to 5
            )
        )
        val output = encodeGeneric(data)
        assertContains(output, "name=test")
        assertContains(output, "## config")
        assertContains(output, "debug=true")
        assertContains(output, "level=5")
    }

    @Test
    fun `generic null in table`() {
        val data = mapOf(
            "items" to listOf(
                mapOf("a" to 1, "b" to null),
                mapOf("a" to 2, "b" to 3),
            )
        )
        val output = encodeGeneric(data)
        assertContains(output, "1|-")
        assertContains(output, "2|3")
    }

    @Test
    fun `generic boolean values`() {
        val data = mapOf("flag" to true, "other" to false)
        val output = encodeGeneric(data)
        assertContains(output, "flag=true")
        assertContains(output, "other=false")
    }

    @Test
    fun `generic string with pipe`() {
        val data = mapOf("val" to "a|b")
        val output = encodeGeneric(data)
        assertContains(output, "val=\"a|b\"")
    }

    @Test
    fun `generic empty string`() {
        val data = mapOf("val" to "")
        val output = encodeGeneric(data)
        assertContains(output, "val=\"\"")
    }

    @Test
    fun `generic nested array in row`() {
        val data = mapOf(
            "users" to listOf(
                mapOf("name" to "Alice", "tags" to listOf("admin", "user")),
                mapOf("name" to "Bob", "tags" to listOf("user")),
            )
        )
        val output = encodeGeneric(data)
        assertContains(output, "@0 Alice|^")
        assertContains(output, "@1 Bob|^")
        assertContains(output, ".tags [2]: admin,user")
    }

    @Test
    fun `generic non-uniform array`() {
        val data = mapOf(
            "items" to listOf(1, "two", true)
        )
        val output = encodeGeneric(data)
        assertContains(output, "items[3]: 1,two,true")
    }

    @Test
    fun `generic string with quotes and pipe`() {
        val data = mapOf("val" to "say \"hello|world\"")
        val output = encodeGeneric(data)
        assertContains(output, """val="say \"hello|world\""""")
    }

    @Test
    fun `generic top level array`() {
        val data = listOf(
            mapOf("id" to 1, "name" to "x"),
            mapOf("id" to 2, "name" to "y"),
        )
        val output = encodeGeneric(data)
        assertContains(output, "## [2]{")
        assertContains(output, "1|x")
        assertContains(output, "2|y")
    }

    @Test
    fun `generic empty map`() {
        val data = emptyMap<String, Any?>()
        val output = encodeGeneric(data)
        assertEquals("GCF profile=generic\n", output)
    }

    @Test
    fun `generic empty list`() {
        val data = emptyList<Any?>()
        val output = encodeGeneric(data)
        assertEquals("GCF profile=generic\n## [0]\n", output)
    }

    // -- Kind Maps --

    @Test
    fun `kind abbreviation map`() {
        assertEquals("fn", kindAbbrev["function"])
        assertEquals("iface", kindAbbrev["interface"])
        assertEquals("ext", kindAbbrev["external"])
        assertEquals("pkg", kindAbbrev["package"])
        assertEquals("svc", kindAbbrev["service"])
        assertEquals("route", kindAbbrev["route_handler"])
    }

    @Test
    fun `kind expand map`() {
        assertEquals("function", kindExpand["fn"])
        assertEquals("interface", kindExpand["iface"])
        assertEquals("external", kindExpand["ext"])
        assertEquals("package", kindExpand["pkg"])
        assertEquals("service", kindExpand["svc"])
        assertEquals("route_handler", kindExpand["route"])
    }

    @Test
    fun `kind maps are inverses`() {
        for ((full, abbrev) in kindAbbrev) {
            assertEquals(full, kindExpand[abbrev], "kindExpand[$abbrev] should equal $full")
        }
        for ((abbrev, full) in kindExpand) {
            assertEquals(abbrev, kindAbbrev[full], "kindAbbrev[$full] should equal $abbrev")
        }
    }

    @Test
    fun `abbreviateKind falls back to input`() {
        assertEquals("fn", abbreviateKind("function"))
        assertEquals("unknown_kind", abbreviateKind("unknown_kind"))
    }

    @Test
    fun `expandKind falls back to input`() {
        assertEquals("function", expandKind("fn"))
        assertEquals("unknown_kind", expandKind("unknown_kind"))
    }
}
