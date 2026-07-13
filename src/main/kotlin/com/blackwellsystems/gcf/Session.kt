package com.blackwellsystems.gcf

/**
 * Session tracks symbols that have been transmitted to a client, enabling
 * subsequent responses to reference them by ID without full retransmission.
 * This makes multi-call workflows progressively cheaper.
 *
 * Thread-safe: all operations are synchronized.
 */
class Session {
    private val symbols = mutableMapOf<String, Int>()
    private var nextID = 0

    /**
     * Returns true if the symbol has been sent in a previous response.
     */
    @Synchronized
    fun transmitted(qname: String): Boolean = qname in symbols

    /**
     * Returns the session-global ID for a previously transmitted symbol.
     * Returns -1 if not found.
     */
    @Synchronized
    fun getID(qname: String): Int = symbols[qname] ?: -1

    /**
     * Marks symbols as transmitted and assigns session-global IDs.
     * Call this after a successful encode to register newly-sent symbols.
     */
    @Synchronized
    fun record(symbolList: List<Symbol>) {
        for (sym in symbolList) {
            if (sym.qualifiedName !in symbols) {
                symbols[sym.qualifiedName] = nextID
                nextID++
            }
        }
    }

    /**
     * Returns the number of symbols tracked in this session.
     */
    @Synchronized
    fun size(): Int = symbols.size

    /**
     * Clears the session state.
     */
    @Synchronized
    fun reset() {
        symbols.clear()
        nextID = 0
    }
}

/**
 * Encode a payload using GCF with session deduplication.
 * Symbols that were already transmitted in prior responses are emitted as
 * bare references (`@N  # previously transmitted`) instead of full declarations.
 * After encoding, newly-sent symbols are recorded in the session.
 */
fun encodeWithSession(payload: Payload, session: Session?): String {
    if (session == null) return encode(payload)

    val b = StringBuilder()

    // Snapshot which symbols were already transmitted BEFORE this response, so we
    // can decide full-vs-bare per symbol. New symbols are then registered to obtain
    // stable session-global IDs (SPEC: @N references are session-scoped and stable
    // across calls, not per-response positional indices).
    data class SymbolEntry(val symbol: Symbol, val isNew: Boolean)
    val entries = payload.symbols.map { s ->
        SymbolEntry(s, !session.transmitted(s.qualifiedName))
    }
    session.record(entries.filter { it.isNew }.map { it.symbol })

    // Session-global ID for any symbol in this response.
    val idOf: (String) -> Int = { qname -> session.getID(qname) }

    // Count valid edges (both endpoints present in this response's symbol set).
    val symbolNames = payload.symbols.map { it.qualifiedName }.toSet()
    val validEdges = payload.edges.count { it.source in symbolNames && it.target in symbolNames }

    // Header with session=true marker. Optional count fields are omitted when zero
    // so the wire matches the canonical graph header (SPEC 5; see graph-encode
    // fixtures and the streaming header in Stream.kt).
    val parts = mutableListOf("GCF profile=graph tool=${payload.tool}")
    if (payload.tokenBudget > 0) parts.add("budget=${payload.tokenBudget}")
    if (payload.tokensUsed > 0) parts.add("tokens=${payload.tokensUsed}")
    parts.add("symbols=${payload.symbols.size}")
    if (validEdges > 0) parts.add("edges=$validEdges")
    if (payload.packRoot.isNotEmpty()) parts.add("pack_root=${payload.packRoot}")
    parts.add("session=true")
    b.append(parts.joinToString(" "))
    b.append('\n')

    // Was this symbol transmitted in a PRIOR response (before this call)?
    val wasNewThisCall = entries.filter { it.isNew }.map { it.symbol.qualifiedName }.toSet()

    // Group by distance.
    val groups = groupByDistance(payload.symbols)
    val groupNames = listOf("targets", "related", "extended")

    for (g in groups) {
        if (g.symbols.isEmpty()) continue

        val name = if (g.distance < groupNames.size) {
            groupNames[g.distance]
        } else {
            "distance_${g.distance}"
        }
        b.append("## $name\n")

        for (s in g.symbols) {
            val id = idOf(s.qualifiedName)
            if (s.qualifiedName !in wasNewThisCall) {
                // Bare reference: symbol was sent in a prior response.
                b.append("@$id  # previously transmitted\n")
            } else {
                // Full declaration.
                val kind = abbreviateKind(s.kind)
                b.append("@$id $kind ${s.qualifiedName} ${"%.2f".format(s.score)} ${s.provenance}\n")
            }
        }
    }

    // Edges section.
    if (payload.edges.isNotEmpty()) {
        b.append("## edges [$validEdges]\n")
        for (e in payload.edges) {
            if (e.source !in symbolNames || e.target !in symbolNames) continue
            b.append("@${idOf(e.target)}<@${idOf(e.source)} ${e.edgeType}")
            if (e.status.isNotEmpty() && e.status != "unchanged") {
                b.append(" ${e.status}")
            }
            b.append('\n')
        }
    }

    return b.toString()
}
