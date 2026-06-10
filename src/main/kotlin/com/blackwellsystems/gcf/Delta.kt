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
            b.append("@$i $kind ${s.qualifiedName} ${"%.2f".format(s.score)} ${s.provenance}\n")
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
