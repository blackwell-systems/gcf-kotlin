package com.blackwellsystems.gcf

/**
 * Encode serializes a Payload into GCF text format.
 */
fun encode(payload: Payload): String {
    val b = StringBuilder()

    // Build symbol index for edge references.
    val symIndex = mutableMapOf<String, Int>()
    payload.symbols.forEachIndexed { i, s ->
        symIndex[s.qualifiedName] = i
    }

    // Count valid edges (both endpoints in symbol index).
    val validEdges = payload.edges.count { symIndex.containsKey(it.source) && symIndex.containsKey(it.target) }

    // Header line.
    b.append("GCF profile=graph tool=${payload.tool} budget=${payload.tokenBudget} tokens=${payload.tokensUsed} symbols=${payload.symbols.size} edges=$validEdges")
    if (payload.packRoot.isNotEmpty()) {
        b.append(" pack_root=${payload.packRoot}")
    }
    b.append('\n')

    // Group symbols by distance.
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

    val groups = mutableListOf<DistanceGroup>()
    var currentDistance = -1
    var currentSymbols = mutableListOf<Symbol>()

    for (s in symbols) {
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
