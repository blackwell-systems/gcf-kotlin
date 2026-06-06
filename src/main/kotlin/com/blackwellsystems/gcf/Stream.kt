package com.blackwellsystems.gcf

import java.io.Writer

/**
 * Options for the streaming encoder.
 */
data class StreamOptions(
    val tokenBudget: Int = 0,
    val tokensUsed: Int = 0,
    val packRoot: String = "",
    val session: Boolean = false
)

/**
 * StreamEncoder writes GCF output incrementally as symbols and edges arrive.
 * Zero buffering: each symbol/edge is written immediately. A trailer summary
 * is emitted on close() with the final counts. Thread-safe via synchronized.
 */
class StreamEncoder(
    private val writer: Writer,
    tool: String,
    options: StreamOptions = StreamOptions()
) {
    private val symIndex = mutableMapOf<String, Int>()
    private var nextID = 0
    private var currentGroup = ""
    private val groupCounts = mutableListOf<Pair<String, Int>>()
    private var edgeCount = 0
    private var edgesStarted = false

    init {
        val parts = mutableListOf("GCF tool=$tool")
        if (options.tokenBudget > 0) parts.add("budget=${options.tokenBudget}")
        if (options.tokensUsed > 0) parts.add("tokens=${options.tokensUsed}")
        if (options.packRoot.isNotEmpty()) parts.add("pack_root=${options.packRoot}")
        if (options.session) parts.add("session=true")
        writer.write(parts.joinToString(" ") + "\n")
        writer.flush()
    }

    /** Emit a symbol line immediately. Group headers are auto-managed. */
    @Synchronized
    fun writeSymbol(s: Symbol) {
        val groupNames = listOf("targets", "related", "extended")
        val groupName = if (s.distance < groupNames.size) groupNames[s.distance] else "distance_${s.distance}"

        if (groupName != currentGroup) {
            writer.write("## $groupName\n")
            currentGroup = groupName
        }

        val id = nextID
        symIndex[s.qualifiedName] = id
        nextID++

        val kind = abbreviateKind(s.kind)
        writer.write("@$id $kind ${s.qualifiedName} ${"%.2f".format(s.score)} ${s.provenance}\n")
        writer.flush()

        val idx = groupCounts.indexOfFirst { it.first == groupName }
        if (idx >= 0) {
            groupCounts[idx] = groupName to (groupCounts[idx].second + 1)
        } else {
            groupCounts.add(groupName to 1)
        }
    }

    /** Emit an edge line immediately. Edges section header auto-emitted on first edge. */
    @Synchronized
    fun writeEdge(e: Edge) {
        val srcIdx = symIndex[e.source] ?: return
        val tgtIdx = symIndex[e.target] ?: return

        if (!edgesStarted) {
            writer.write("## edges [?]\n")
            edgesStarted = true
        }

        var line = "@$tgtIdx<@$srcIdx ${e.edgeType}"
        if (e.status.isNotEmpty() && e.status != "unchanged") {
            line += " ${e.status}"
        }
        writer.write(line + "\n")
        writer.flush()
        edgeCount++
    }

    /** Emit a bare reference (session mode). */
    @Synchronized
    fun writeBareRef(qname: String, distance: Int) {
        val groupNames = listOf("targets", "related", "extended")
        val groupName = if (distance < groupNames.size) groupNames[distance] else "distance_$distance"

        if (groupName != currentGroup) {
            writer.write("## $groupName\n")
            currentGroup = groupName
        }

        val id = nextID
        symIndex[qname] = id
        nextID++
        writer.write("@$id  # previously transmitted\n")
        writer.flush()

        val idx = groupCounts.indexOfFirst { it.first == groupName }
        if (idx >= 0) {
            groupCounts[idx] = groupName to (groupCounts[idx].second + 1)
        } else {
            groupCounts.add(groupName to 1)
        }
    }

    /** Emit ## _summary trailer with final counts. */
    @Synchronized
    fun close() {
        val sections = mutableListOf<String>()
        for ((g, c) in groupCounts) {
            if (c > 0) sections.add("$g:$c")
        }
        if (edgeCount > 0) sections.add("edges:$edgeCount")

        writer.write("## _summary symbols=$nextID edges=$edgeCount sections=${sections.joinToString(",")}\n")
        writer.flush()
    }

    /** Number of symbols written so far. */
    val symbolCount: Int get() = nextID

    /** Number of edges written so far. */
    val edgeCountValue: Int get() = edgeCount
}
