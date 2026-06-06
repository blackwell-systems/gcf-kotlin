<p align="center">
  <a href="https://github.com/blackwell-systems"><img src="https://raw.githubusercontent.com/blackwell-systems/blackwell-docs-theme/main/badge-trademark.svg" alt="Blackwell Systems"></a>
  <a href="https://github.com/blackwell-systems/gcf-kotlin/actions"><img src="https://github.com/blackwell-systems/gcf-kotlin/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

# gcf-kotlin

Kotlin/JVM implementation of [GCF (Graph Compact Format)](https://gcformat.com/) -- the most token-efficient wire format for LLMs. A drop-in alternative to JSON and TOON for any structured data.

**79% fewer input tokens than JSON. 75% fewer output tokens. 52% smaller than TOON. 100% LLM comprehension at 500 symbols, where JSON scores 76.9% and TOON scores 92.3%.**

Docs: [gcformat.com](https://gcformat.com/) · [Playground](https://gcformat.com/playground.html) · [GCF vs TOON](https://gcformat.com/guide/vs-toon.html)

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.blackwellsystems:gcf:0.1.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.blackwellsystems:gcf:0.1.0'
}
```

### Maven

```xml
<dependency>
    <groupId>com.blackwellsystems</groupId>
    <artifactId>gcf</artifactId>
    <version>0.1.0</version>
</dependency>
```

Don't want to change code? Use the [MCP proxy](https://github.com/blackwell-systems/gcf-proxy) for zero-code adoption.

## Quick Start

```kotlin
import com.blackwellsystems.gcf.*

val payload = Payload(
    tool = "context_for_task",
    tokenBudget = 5000,
    tokensUsed = 1847,
    symbols = listOf(
        Symbol(qualifiedName = "pkg.AuthMiddleware", kind = "function", score = 0.78, provenance = "lsp_resolved", distance = 0),
        Symbol(qualifiedName = "pkg.NewServer", kind = "function", score = 0.54, provenance = "lsp_resolved", distance = 1),
    ),
    edges = listOf(
        Edge(source = "pkg.NewServer", target = "pkg.AuthMiddleware", edgeType = "calls")
    )
)

val output = encode(payload)
```

Output:
```
GCF tool=context_for_task budget=5000 tokens=1847 symbols=2 edges=1
## targets
@0 fn pkg.AuthMiddleware 0.78 lsp_resolved
## related
@1 fn pkg.NewServer 0.54 lsp_resolved
## edges [1]
@0<@1 calls
```

## Decode

```kotlin
val p = decode(input)
println("${p.tool} ${p.symbols.size} symbols ${p.edges.size} edges")
```

Throws `DecodeException` on invalid input.

## Session Deduplication

Track transmitted symbols across multiple tool responses. Previously-sent symbols become bare references instead of full declarations:

```kotlin
val session = Session()

val out1 = encodeWithSession(payload1, session) // full declarations
val out2 = encodeWithSession(payload2, session) // reused symbols as "@N  # previously transmitted"
```

By the 5th call in a session: 92.7% token savings vs JSON.

## Delta Encoding

When the consumer already has a prior context pack, send only what changed:

```kotlin
val delta = DeltaPayload(
    tool = "context_for_task",
    baseRoot = "aaa111",
    newRoot = "bbb222",
    removed = listOf(Symbol(qualifiedName = "pkg.OldFunc", kind = "function")),
    added = listOf(Symbol(qualifiedName = "pkg.NewFunc", kind = "function", score = 0.85, provenance = "rwr")),
    deltaTokens = 30,
    fullTokens = 200
)

val output = encodeDelta(delta)
```

81.2% savings on re-queries where the pack changed slightly.

## Generic Encoding

Encode any value (not just graph payloads) into GCF tabular format:

```kotlin
val data = mapOf(
    "employees" to listOf(
        mapOf("id" to 1, "name" to "Alice", "department" to "Engineering", "salary" to 95000),
        mapOf("id" to 2, "name" to "Bob", "department" to "Sales", "salary" to 72000),
    )
)
val output = encodeGeneric(data)
```

Output:
```
## employees [2]{department,id,name,salary}
Engineering|1|Alice|95000
Sales|2|Bob|72000
```

Works on maps, lists, and primitives. Arrays of uniform maps get tabular rows. Nested maps use `## key` section headers.

## API

| Function | Description |
|----------|-------------|
| `encode(payload: Payload): String` | Encode a graph payload to GCF text |
| `encodeGeneric(data: Any?): String` | Encode any value to GCF tabular format |
| `decode(input: String): Payload` | Parse GCF text back to a Payload |
| `encodeWithSession(payload: Payload, session: Session?): String` | Encode with session deduplication |
| `encodeDelta(delta: DeltaPayload): String` | Encode a delta (added/removed only) |
| `Session()` | Create a new session tracker (thread-safe) |

## Types

| Type | Purpose |
|------|---------|
| `Payload` | Full GCF payload: tool, budget, symbols, edges, pack root |
| `Symbol` | Graph node: qualified name, kind, score, provenance, distance |
| `Edge` | Directed relationship: source, target, edge type |
| `DeltaPayload` | Diff between two packs: added/removed symbols and edges |
| `Session` | Thread-safe tracker for multi-call deduplication |
| `Components` | Score breakdown: blast radius, confidence, recency, distance |
| `DecodeException` | Thrown on invalid GCF input |
| `kindAbbrev` / `kindExpand` | Bidirectional kind abbreviation maps |

## Links

- [Documentation](https://gcformat.com/)
- [Playground](https://gcformat.com/playground.html)
- [Specification](https://github.com/blackwell-systems/gcf)
- [Go library](https://github.com/blackwell-systems/gcf-go)
- [TypeScript library](https://github.com/blackwell-systems/gcf-typescript)
- [Python library](https://github.com/blackwell-systems/gcf-python)
- [Rust library](https://github.com/blackwell-systems/gcf-rust)
- [MCP Proxy](https://github.com/blackwell-systems/gcf-proxy) (zero-code adoption)
- [GCF vs TOON](https://gcformat.com/guide/vs-toon.html)

## License

MIT
