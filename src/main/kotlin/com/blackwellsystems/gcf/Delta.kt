package com.blackwellsystems.gcf

/**
 * EncodeDelta serializes a DeltaPayload into GCF delta format.
 */
fun encodeDelta(delta: DeltaPayload): String {
    val b = StringBuilder()

    // Header.
    val savings = if (delta.fullTokens > 0) {
        100.0 * (1.0 - delta.deltaTokens.toDouble() / delta.fullTokens.toDouble())
    } else {
        0.0
    }
    b.append("GCF profile=graph tool=${delta.tool} delta=true base_root=${delta.baseRoot} new_root=${delta.newRoot} tokens=${delta.deltaTokens} savings=${"%.0f".format(savings)}%\n")

    // Removed symbols: short references (consumer already has the full declaration).
    if (delta.removed.isNotEmpty()) {
        b.append("## removed\n")
        for (s in delta.removed) {
            val kind = abbreviateKind(s.kind)
            b.append("$kind ${s.qualifiedName}\n")
        }
    }

    // Added symbols: full declarations (consumer doesn't have these).
    if (delta.added.isNotEmpty()) {
        b.append("## added\n")
        delta.added.forEachIndexed { i, s ->
            val kind = abbreviateKind(s.kind)
            b.append("@$i $kind ${s.qualifiedName} ${"%.2f".format(s.score)} ${s.provenance} ${s.distance}\n")
        }
    }

    // Removed edges.
    if (delta.removedEdges.isNotEmpty()) {
        b.append("## edges_removed\n")
        for (e in delta.removedEdges) {
            b.append("${e.source} -> ${e.target} ${e.edgeType}\n")
        }
    }

    // Added edges.
    if (delta.addedEdges.isNotEmpty()) {
        b.append("## edges_added\n")
        for (e in delta.addedEdges) {
            b.append("${e.source} -> ${e.target} ${e.edgeType}\n")
        }
    }

    return b.toString()
}

/**
 * Parse a `source -> target type` delta edge line.
 */
private fun parseDeltaEdge(line: String): Edge {
    val idx = line.indexOf(" -> ")
    if (idx < 0) {
        throw IllegalArgumentException("malformed_delta: edge line missing ' -> ': \"$line\"")
    }
    val source = line.substring(0, idx)
    val rest = line.substring(idx + 4).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (rest.size != 2) {
        throw IllegalArgumentException("malformed_delta: edge line \"$line\" must be 'source -> target type'")
    }
    return Edge(source = source, target = rest[0], edgeType = rest[1])
}

/**
 * DecodeDelta parses a GCF graph delta wire payload (as produced by encodeDelta)
 * back into a DeltaPayload. Kind abbreviations on removed/added lines are expanded
 * to their full form so the result matches a base snapshot's symbol identities.
 */
fun decodeDelta(wire: String): DeltaPayload {
    val lines = wire.trimEnd('\n').split("\n")
    if (lines.isEmpty() || lines[0].isEmpty()) {
        throw IllegalArgumentException("missing_header: empty delta payload")
    }
    val header = lines[0].trimEnd('\r')
    if (!header.startsWith("GCF profile=graph")) {
        throw IllegalArgumentException("missing_profile: delta header must begin with 'GCF profile=graph'")
    }

    var tool = ""
    var baseRoot = ""
    var newRoot = ""
    for (field in header.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        val kv = field.split("=", limit = 2)
        if (kv.size != 2) continue
        when (kv[0]) {
            "tool" -> tool = kv[1]
            "base_root" -> baseRoot = kv[1]
            "new_root" -> newRoot = kv[1]
        }
    }

    val removed = mutableListOf<Symbol>()
    val added = mutableListOf<Symbol>()
    val removedEdges = mutableListOf<Edge>()
    val addedEdges = mutableListOf<Edge>()

    var section = ""
    for (raw in lines.drop(1)) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        if (line.startsWith("## ")) {
            section = line.substring(3).trim()
            when (section) {
                "removed", "added", "edges_removed", "edges_added" -> {}
                else -> throw IllegalArgumentException("malformed_delta: unknown section \"$section\"")
            }
            continue
        }
        when (section) {
            "removed" -> {
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size != 2) {
                    throw IllegalArgumentException("malformed_delta: removed line \"$line\" must be 'kind qname'")
                }
                removed.add(Symbol(kind = expandKind(parts[0]), qualifiedName = parts[1]))
            }
            "added" -> {
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size != 6) {
                    throw IllegalArgumentException("malformed_delta: added line \"$line\" must be '@id kind qname score provenance distance'")
                }
                val score = parts[3].toDoubleOrNull()
                    ?: throw IllegalArgumentException("malformed_delta: invalid added score \"${parts[3]}\"")
                val dist = parts[5].toIntOrNull()
                    ?: throw IllegalArgumentException("malformed_delta: invalid added distance \"${parts[5]}\"")
                added.add(
                    Symbol(
                        kind = expandKind(parts[1]),
                        qualifiedName = parts[2],
                        score = score,
                        provenance = parts[4],
                        distance = dist,
                    )
                )
            }
            "edges_removed" -> removedEdges.add(parseDeltaEdge(line))
            "edges_added" -> addedEdges.add(parseDeltaEdge(line))
            else -> throw IllegalArgumentException("malformed_delta: data line \"$line\" before any section header")
        }
    }

    return DeltaPayload(
        tool = tool,
        baseRoot = baseRoot,
        newRoot = newRoot,
        removed = removed,
        added = added,
        removedEdges = removedEdges,
        addedEdges = addedEdges,
    )
}

/**
 * VerifyDelta verifies that applying a delta to a base snapshot produces the
 * expected new_root. Returns the resulting (symbols, edges) if verification
 * succeeds, or throws if it fails. Mirrors gcf-go VerifyDelta.
 */
fun verifyDelta(
    baseSymbols: List<Symbol>,
    baseEdges: List<Edge>,
    removed: List<Symbol>,
    added: List<Symbol>,
    removedEdges: List<Edge>,
    addedEdges: List<Edge>,
    expectedNewRoot: String,
): Pair<List<Symbol>, List<Edge>> {
    // Index base symbols by identity (kind, qname).
    val symMap = LinkedHashMap<Pair<String, String>, Symbol>(baseSymbols.size)
    for (s in baseSymbols) {
        symMap[Pair(s.kind, s.qualifiedName)] = s
    }

    // Apply removals.
    for (s in removed) {
        val key = Pair(s.kind, s.qualifiedName)
        if (!symMap.containsKey(key)) {
            throw IllegalArgumentException("delta_invalid: removing symbol ${s.kind} ${s.qualifiedName} that does not exist in base")
        }
        symMap.remove(key)
    }

    // Apply additions.
    for (s in added) {
        val key = Pair(s.kind, s.qualifiedName)
        if (symMap.containsKey(key)) {
            throw IllegalArgumentException("delta_invalid: adding symbol ${s.kind} ${s.qualifiedName} that already exists")
        }
        symMap[key] = s
    }

    val resultSymbols = symMap.values.toList()

    // Index base edges.
    val edgeMap = LinkedHashMap<Triple<String, String, String>, Edge>(baseEdges.size)
    for (e in baseEdges) {
        edgeMap[Triple(e.source, e.target, e.edgeType)] = e
    }

    // Apply edge removals.
    for (e in removedEdges) {
        val key = Triple(e.source, e.target, e.edgeType)
        if (!edgeMap.containsKey(key)) {
            throw IllegalArgumentException("delta_invalid: removing edge ${e.source} -> ${e.target} ${e.edgeType} that does not exist")
        }
        edgeMap.remove(key)
    }

    // Apply edge additions.
    for (e in addedEdges) {
        val key = Triple(e.source, e.target, e.edgeType)
        if (edgeMap.containsKey(key)) {
            throw IllegalArgumentException("delta_invalid: adding edge ${e.source} -> ${e.target} ${e.edgeType} that already exists")
        }
        edgeMap[key] = e
    }

    val resultEdges = edgeMap.values.toList()

    // Verify pack root.
    val computedRoot = packRoot(resultSymbols, resultEdges)
    if (computedRoot != expectedNewRoot) {
        throw IllegalArgumentException("root_mismatch: computed $computedRoot, expected $expectedNewRoot")
    }

    return Pair(resultSymbols, resultEdges)
}
