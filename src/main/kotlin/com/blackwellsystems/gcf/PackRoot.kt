package com.blackwellsystems.gcf

// Graph-profile pack root (SPEC Section 10.2, gcf-pack-root-v1).
//
// Computes the canonical content-addressed hash of a graph snapshot (symbols +
// edges), byte-for-byte interoperable with the gcf-go reference (packroot.go)
// and the other SDKs. Reuses the generic pack root's UTF-8 byte-order comparator
// (byteOrder), canonical number formatter (formatNumberValue), and SHA-256 helper
// (sha256Hex), plus the shared kind-abbreviation map (kindAbbrev).

/**
 * Compute the canonical pack root hash for a graph snapshot using the
 * gcf-pack-root-v1 algorithm (SPEC Section 10.2). Two implementations given the
 * same logical graph MUST produce the same result.
 *
 * Each symbol becomes a tab-separated record
 *   `S\t{kindAbbrev}\t{qualifiedName}\t{score}\t{provenance}\t{distance}\n`
 * where score is the canonical shortest-decimal number (not the wire's 2-decimal
 * form). Each edge becomes
 *   `E\t{srcKind}\t{source}\t{tgtKind}\t{target}\t{edgeType}\n`
 * with endpoint kinds resolved from the symbol set (disambiguating same qname /
 * different kind). Symbol and edge records are sorted independently by UTF-8 byte
 * order, then concatenated (symbols first) and hashed.
 */
fun packRoot(symbols: List<Symbol>, edges: List<Edge>): String {
    val symRecords = symbols.map { s ->
        val kind = kindAbbrev[s.kind] ?: s.kind
        val score = formatNumberValue(s.score)
        "S\t$kind\t${s.qualifiedName}\t$score\t${s.provenance}\t${s.distance}\n"
    }.sortedWith(byteOrder)

    // Resolve edge endpoints to their symbol identity (kind, qname).
    val symKindMap = HashMap<String, String>(symbols.size)
    for (s in symbols) {
        symKindMap[s.qualifiedName] = kindAbbrev[s.kind] ?: s.kind
    }
    val edgeRecords = edges.map { e ->
        val srcKind = symKindMap[e.source] ?: ""
        val tgtKind = symKindMap[e.target] ?: ""
        "E\t$srcKind\t${e.source}\t$tgtKind\t${e.target}\t${e.edgeType}\n"
    }.sortedWith(byteOrder)

    val canonical = symRecords.joinToString("") + edgeRecords.joinToString("")
    return "sha256:" + sha256Hex(canonical)
}
