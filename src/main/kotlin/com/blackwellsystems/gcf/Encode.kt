package com.blackwellsystems.gcf

/**
 * Encode serializes a Payload into GCF text format.
 */
fun encode(payload: Payload): String {
    val b = StringBuilder()

    // Group symbols by distance (sorted by score descending within each group).
    val groups = groupByDistance(payload.symbols)

    // Build the symbol index AFTER sorting so @N IDs are sequential in output order.
    val symIndex = mutableMapOf<String, Int>()
    var nextID = 0
    for (g in groups) {
        for (s in g.symbols) {
            symIndex[s.qualifiedName] = nextID
            nextID++
        }
    }

    // Count valid edges (both endpoints in symbol index).
    val validEdges = payload.edges.count { symIndex.containsKey(it.source) && symIndex.containsKey(it.target) }

    // Header line. Omit budget/tokens/edges when zero (SPEC 16.1), matching the reference.
    b.append("GCF profile=graph tool=${payload.tool}")
    if (payload.tokenBudget > 0) b.append(" budget=${payload.tokenBudget}")
    if (payload.tokensUsed > 0) b.append(" tokens=${payload.tokensUsed}")
    b.append(" symbols=${payload.symbols.size}")
    if (validEdges > 0) b.append(" edges=$validEdges")
    if (payload.packRoot.isNotEmpty()) {
        b.append(" pack_root=${payload.packRoot}")
    }
    b.append('\n')

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
            val idx = symIndex[s.qualifiedName] ?: continue
            val kind = abbreviateKind(s.kind)
            b.append("@$idx $kind ${s.qualifiedName} ${"%.2f".format(s.score)} ${s.provenance}\n")
        }
    }

    // Edges section. Order edges by source ID then target ID (then edge type for
    // parallel edges) so the wire is canonical regardless of the order edges were
    // provided (SPEC 16.1). Edge reordering is decode-invariant (edges are a set)
    // and does not affect pack_root, which sorts edge records independently.
    if (payload.edges.isNotEmpty()) {
        val resolved = payload.edges.mapNotNull { e ->
            val srcIdx = symIndex[e.source] ?: return@mapNotNull null
            val tgtIdx = symIndex[e.target] ?: return@mapNotNull null
            ResolvedEdge(srcIdx, tgtIdx, e.edgeType, e.status)
        }.sortedWith(compareBy({ it.srcIdx }, { it.tgtIdx }, { it.edgeType }))

        b.append("## edges [$validEdges]\n")
        for (e in resolved) {
            b.append("@${e.tgtIdx}<@${e.srcIdx} ${e.edgeType}")
            if (e.status.isNotEmpty() && e.status != "unchanged") {
                b.append(" ${e.status}")
            }
            b.append('\n')
        }
    }

    return b.toString()
}

internal data class ResolvedEdge(
    val srcIdx: Int,
    val tgtIdx: Int,
    val edgeType: String,
    val status: String,
)

internal data class DistanceGroup(val distance: Int, val symbols: List<Symbol>)

internal fun groupByDistance(symbols: List<Symbol>): List<DistanceGroup> {
    if (symbols.isEmpty()) return emptyList()

    // Sort by distance ascending, then score descending within each group (stable),
    // matching the reference so IDs and group membership are canonical regardless of
    // input order (SPEC 16.1).
    val sorted = symbols.sortedWith(compareBy<Symbol> { it.distance }.thenByDescending { it.score })

    val groups = mutableListOf<DistanceGroup>()
    var currentDistance = -1
    var currentSymbols = mutableListOf<Symbol>()

    for (s in sorted) {
        if (s.distance != currentDistance) {
            if (currentSymbols.isNotEmpty()) {
                groups.add(DistanceGroup(currentDistance, currentSymbols))
            }
            currentDistance = s.distance
            currentSymbols = mutableListOf()
        }
        currentSymbols.add(s)
    }
    if (currentSymbols.isNotEmpty()) {
        groups.add(DistanceGroup(currentDistance, currentSymbols))
    }

    return groups
}
