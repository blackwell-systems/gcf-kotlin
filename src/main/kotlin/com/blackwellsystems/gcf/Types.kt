package com.blackwellsystems.gcf

import kotlinx.serialization.Serializable

/**
 * Symbol represents a node in a GCF payload.
 */
@Serializable
data class Symbol(
    val qualifiedName: String,
    val kind: String,
    val score: Double = 0.0,
    val provenance: String = "",
    val distance: Int = 0,
    val signature: String = "",
    val components: Components = Components()
)

/**
 * Components holds the score breakdown for a symbol.
 */
@Serializable
data class Components(
    val blastRadius: Double = 0.0,
    val confidence: Double = 0.0,
    val recency: Double = 0.0,
    val distance: Double = 0.0
)

/**
 * Edge represents a directed relationship in a GCF payload.
 */
@Serializable
data class Edge(
    val source: String,
    val target: String,
    val edgeType: String,
    val status: String = ""
)

/**
 * Payload is the input/output structure for GCF encoding/decoding.
 */
@Serializable
data class Payload(
    val tool: String,
    val tokensUsed: Int = 0,
    val tokenBudget: Int = 0,
    val packRoot: String = "",
    val symbols: List<Symbol> = emptyList(),
    val edges: List<Edge> = emptyList()
)

/**
 * DeltaPayload represents the diff between a prior context pack and the
 * current result. Used for incremental context delivery.
 */
@Serializable
data class DeltaPayload(
    val tool: String,
    val baseRoot: String,
    val newRoot: String,
    val removed: List<Symbol> = emptyList(),
    val added: List<Symbol> = emptyList(),
    val removedEdges: List<Edge> = emptyList(),
    val addedEdges: List<Edge> = emptyList(),
    val deltaTokens: Int = 0,
    val fullTokens: Int = 0
)

/**
 * Exception thrown when GCF decoding fails.
 */
class DecodeException(message: String) : Exception(message)
