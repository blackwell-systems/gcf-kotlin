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

    // Build local ID mapping for this response.
    val localIndex = mutableMapOf<String, Int>()
    payload.symbols.forEachIndexed { i, s ->
        localIndex[s.qualifiedName] = i
    }

    // Count valid edges.
    val validEdges = payload.edges.count { localIndex.containsKey(it.source) && localIndex.containsKey(it.target) }

    // Header with session=true marker.
    b.append("GCF profile=graph tool=${payload.tool} budget=${payload.tokenBudget} tokens=${payload.tokensUsed} symbols=${payload.symbols.size} edges=$validEdges session=true")
    if (payload.packRoot.isNotEmpty()) {
        b.append(" pack_root=${payload.packRoot}")
    }
    b.append('\n')

    // Track which are new.
    data class SymbolEntry(val symbol: Symbol, val isNew: Boolean)
    val entries = payload.symbols.map { s ->
        SymbolEntry(s, !session.transmitted(s.qualifiedName))
    }

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
            val idx = localIndex[s.qualifiedName] ?: continue
            if (session.transmitted(s.qualifiedName)) {
                // Bare reference: symbol was sent in a prior response.
                b.append("@$idx  # previously transmitted\n")
            } else {
                // Full declaration.
                val kind = abbreviateKind(s.kind)
                b.append("@$idx $kind ${s.qualifiedName} ${"%.2f".format(s.score)} ${s.provenance}\n")
            }
        }
    }

    // Edges section.
    if (payload.edges.isNotEmpty()) {
        b.append("## edges [$validEdges]\n")
        for (e in payload.edges) {
            val srcIdx = localIndex[e.source] ?: continue
            val tgtIdx = localIndex[e.target] ?: continue
            b.append("@$tgtIdx<@$srcIdx ${e.edgeType}")
            if (e.status.isNotEmpty() && e.status != "unchanged") {
                b.append(" ${e.status}")
            }
            b.append('\n')
        }
    }

    // Record all new symbols in the session.
    val newSymbols = entries.filter { it.isNew }.map { it.symbol }
    session.record(newSymbols)

    return b.toString()
}
