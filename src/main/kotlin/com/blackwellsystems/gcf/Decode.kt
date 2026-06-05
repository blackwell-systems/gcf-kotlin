package com.blackwellsystems.gcf

/**
 * Decode parses GCF text back into a Payload.
 *
 * @throws DecodeException on invalid input (missing GCF prefix, missing tool, <5 fields, unknown edge IDs)
 */
fun decode(input: String): Payload {
    val lines = input.split("\n")
    if (lines.isEmpty()) {
        throw DecodeException("gcf: empty input")
    }

    val header = lines[0]
    if (!header.startsWith("GCF ")) {
        throw DecodeException("gcf: invalid header, expected 'GCF ...' got \"$header\"")
    }

    val headerResult = parseHeader(header.substring(4))
    if (headerResult.tool.isEmpty()) {
        throw DecodeException("gcf: header missing required 'tool' field")
    }

    val symbols = mutableListOf<Symbol>()
    val symByID = mutableMapOf<Int, Symbol>()
    var currentDistance = 0
    var inEdges = false
    val edges = mutableListOf<Edge>()

    for (line in lines.drop(1)) {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isEmpty()) continue

        // Group header.
        if (trimmed.startsWith("## ")) {
            val group = trimmed.substring(3)
            inEdges = group == "edges"
            if (!inEdges) {
                currentDistance = when (group) {
                    "targets" -> 0
                    "related" -> 1
                    "extended" -> 2
                    else -> {
                        if (group.startsWith("distance_")) {
                            group.substring(9).toIntOrNull() ?: currentDistance
                        } else {
                            currentDistance
                        }
                    }
                }
            }
            continue
        }

        // Comment.
        if (trimmed.startsWith("# ")) continue

        if (inEdges) {
            val edge = parseEdgeLine(trimmed, symByID)
            edges.add(edge)
        } else {
            val (sym, id) = parseSymbolLine(trimmed, currentDistance)
            symbols.add(sym)
            symByID[id] = sym
        }
    }

    return Payload(
        tool = headerResult.tool,
        tokenBudget = headerResult.tokenBudget,
        tokensUsed = headerResult.tokensUsed,
        packRoot = headerResult.packRoot,
        symbols = symbols,
        edges = edges
    )
}

private data class HeaderResult(
    val tool: String = "",
    val tokenBudget: Int = 0,
    val tokensUsed: Int = 0,
    val packRoot: String = ""
)

private fun parseHeader(fields: String): HeaderResult {
    var tool = ""
    var tokenBudget = 0
    var tokensUsed = 0
    var packRoot = ""

    for (part in fields.trim().split("\\s+".toRegex())) {
        val kv = part.split("=", limit = 2)
        if (kv.size != 2) continue
        when (kv[0]) {
            "tool" -> tool = kv[1]
            "budget" -> tokenBudget = kv[1].toIntOrNull()
                ?: throw DecodeException("gcf: invalid budget \"${kv[1]}\"")
            "tokens" -> tokensUsed = kv[1].toIntOrNull()
                ?: throw DecodeException("gcf: invalid tokens \"${kv[1]}\"")
            "pack_root" -> packRoot = kv[1]
            "symbols" -> { /* informational, reconstructed from parsed symbols */ }
        }
    }

    return HeaderResult(tool, tokenBudget, tokensUsed, packRoot)
}

private fun parseSymbolLine(line: String, distance: Int): Pair<Symbol, Int> {
    if (!line.startsWith("@")) {
        throw DecodeException("gcf: expected symbol line starting with @, got \"$line\"")
    }

    val parts = line.trim().split("\\s+".toRegex())
    if (parts.size < 5) {
        throw DecodeException("gcf: symbol line needs at least 5 fields, got ${parts.size} in \"$line\"")
    }

    val idStr = parts[0].substring(1) // strip @
    val id = idStr.toIntOrNull()
        ?: throw DecodeException("gcf: invalid symbol id \"$idStr\"")

    val kind = expandKind(parts[1])
    val qname = parts[2]
    val score = parts[3].toDoubleOrNull()
        ?: throw DecodeException("gcf: invalid score \"${parts[3]}\"")
    val provenance = parts[4]

    return Pair(
        Symbol(
            qualifiedName = qname,
            kind = kind,
            score = score,
            provenance = provenance,
            distance = distance
        ),
        id
    )
}

private fun parseEdgeLine(line: String, symByID: Map<Int, Symbol>): Edge {
    val parts = line.trim().split("\\s+".toRegex())
    if (parts.size < 2) {
        throw DecodeException("gcf: edge line needs at least 2 fields, got \"$line\"")
    }

    val ref = parts[0]
    val ltIdx = ref.indexOf('<')
    if (ltIdx < 0) {
        throw DecodeException("gcf: edge line missing '<' separator in \"$ref\"")
    }

    val targetIDStr = ref.substring(1, ltIdx) // strip leading @
    val sourceIDStr = ref.substring(ltIdx + 2) // strip <@

    val targetID = targetIDStr.toIntOrNull()
        ?: throw DecodeException("gcf: invalid target id \"$targetIDStr\"")
    val sourceID = sourceIDStr.toIntOrNull()
        ?: throw DecodeException("gcf: invalid source id \"$sourceIDStr\"")

    val targetSym = symByID[targetID]
        ?: throw DecodeException("gcf: edge references unknown symbol id(s): target=$targetID source=$sourceID")
    val sourceSym = symByID[sourceID]
        ?: throw DecodeException("gcf: edge references unknown symbol id(s): target=$targetID source=$sourceID")

    val edgeType = parts[1]
    val status = if (parts.size >= 3) parts[2] else ""

    return Edge(
        source = sourceSym.qualifiedName,
        target = targetSym.qualifiedName,
        edgeType = edgeType,
        status = status
    )
}
