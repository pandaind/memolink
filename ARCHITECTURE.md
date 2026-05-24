# MemoLink — Detailed Architecture & Design

---

## Vision

MemoLink turns a folder of plain Markdown files into an intelligent knowledge graph that both humans and AI agents can navigate. The design goal is **simple + powerful + AI-ready** — no vector databases, no graph databases, no distributed systems.

---

## High-Level Architecture

```
Markdown Files (*.md)
        │
        ▼
┌─────────────────────────────────────────────────────┐
│                   memolink-core                     │
│                                                     │
│  MdFileScannerService  → finds all .md files        │
│  MdFileParserService   → extracts metadata          │
│  RelationshipEngine    → scores pairwise edges      │
│  GraphBuilderService   → orchestrates the pipeline  │
│  GraphSearchService    → Lucene full-text index     │
│  GraphTraversalService → BFS graph traversal        │
│  GraphWatchService     → debounced file watcher     │
└────────────────────┬────────────────────────────────┘
                     │  KnowledgeGraph (in-memory)
          ┌──────────┼──────────┐
          ▼          ▼          ▼
   Viewer Starter  AI Starter  MCP Server
   (REST + UI)    (@Tool)     (stdio/MCP)
```

---

## Module Details

---

### `memolink-core`

**Package:** `ist.logic.core`  
**Type:** Pure Java library — zero Spring, zero framework dependency.

This is the most important module. Everything else is a thin integration layer on top of it.

#### Pipeline

```
MdFileScannerService.scan(rootDir)
        │  List<Path>
        ▼
MdFileParserService.parse(path) × N
        │  List<MdFileMetadata>
        ▼
RelationshipEngine.buildEdges(files)
        │  List<GraphEdge>
        ▼
new KnowledgeGraph(files, edges)
```

All steps are orchestrated by `GraphBuilderService.build(Path rootDir)`.  
For incremental updates, `GraphBuilderService.buildIncremental(KnowledgeGraph current, Set<Path> changedPaths)` re-parses only changed files and carries unchanged notes forward.

#### `MdFileMetadata`

Immutable parsed representation of one `.md` file:

```java
String id;          // filename, e.g. "spring-boot.md"
String title;       // first H1 heading, or filename stem if no H1
String content;     // raw markdown text
Path   filePath;    // absolute path on disk
Set<String> wikiLinks;  // [[link-target]] references, normalised
Set<String> tags;       // #tag values
Set<String> keywords;   // significant words extracted from content
Set<String> headings;   // all ## heading text
```

#### `GraphEdge`

```java
record GraphEdge(String source, String target, int weight, Set<String> reasons)
```

#### Relationship Scoring (`RelationshipEngine`)

Edges are built using an inverted index — only pairs that share at least one signal are ever scored, reducing complexity from O(n²) to O(n·k) where k is average token fan-out.

| Signal | Score | Cap |
|--------|-------|-----|
| A wiki-links B, or B wiki-links A | +5 | — |
| Each shared tag | +2 | 3 tags max → +6 |
| Each shared keyword | +1 | 5 keywords max → +5 |

Maximum possible edge weight: **16** (wiki-link + 3 shared tags + 5 shared keywords).  
Only pairs with score > 0 produce an edge in the graph.

#### `KnowledgeGraph`

Holds two parallel structures:
- `List<GraphNode>` + `List<GraphEdge>` — serialised as JSON by Jackson for the REST API.
- `Map<String, MdFileMetadata> fileIndex` — fast O(1) lookup by file ID.
- `Map<String, List<GraphEdge>> adjacency` — bidirectional adjacency map (both source→target and target→source stored) for traversal.

Key methods:
```java
MdFileMetadata           getMdFile(String id)
List<GraphEdge>          getNeighborEdges(String fileId)
Collection<MdFileMetadata> getAllMdFiles()
int                      size()
```

#### `GraphSearchService`

Wraps Apache Lucene 9.12.0 with an in-memory `ByteBuffersDirectory`.  
Indexed fields per document: `id`, `title`, `content`, `tags`, `headings`.  
Queries use `MultiFieldQueryParser` with AND default operator. Raw query is tried first; escaping is used as fallback only when the raw form fails to parse.  
Returns a ranked `List<SearchResult>` (each entry carries `id`, `title`, and `score`).

#### `GraphWatchService`

Watches `rootDir` recursively for `.md` file changes using the Java NIO `WatchService`.  
Accumulates changed paths during a 500 ms debounce window (`ConcurrentHashMap.newKeySet()`), then fires `Consumer<Set<Path>> onChanged` with the full set of changed paths.  
Uses `AtomicReference<ScheduledFuture<?>>` for lock-free debounce management.  
Thread names: `"memolink-watcher"`, `"memolink-debouncer"`.

#### `GraphTraversalService`

Breadth-first traversal from a start node with three configurable limits:

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `depth` | 2 | Max BFS hops |
| `maxNeighbors` | 5 | Max edges to follow per node |
| `minWeight` | 3 | Edges below this weight are pruned |

Neighbors at each level are sorted by descending edge weight before the `maxNeighbors` limit is applied, so the strongest relationships are always explored first.

---

### `memolink-viewer-starter`

**Package:** `ist.logic.viewer`  
**Type:** Spring Boot auto-configuration starter (not a runnable app).

#### What it provides

- `MemoLinkViewerAutoConfiguration` — registers all beans below automatically when added as a dependency.
- `KnowledgeGraph` bean — built from `memolink.notes-dir` on startup; incrementally rebuilt on file changes.
- `GraphSearchService` bean — Lucene index populated from the graph.
- `GraphTraversalService` bean — stateless, reusable.
- `GraphWatchService` bean — debounced file watcher that triggers incremental rebuild.
- `GraphController` bean — Spring MVC `@RestController` at `/api/*`.
- Static resources — `index.html` with a full Cytoscape.js dark-themed graph UI.

All beans are `@ConditionalOnMissingBean` — the consuming application can override any of them.  
The auto-configuration is `@ConditionalOnWebApplication` — it is a no-op in non-web apps.

#### Auto-configuration activation

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
→ ist.logic.viewer.MemoLinkViewerAutoConfiguration
```

#### Configuration properties

```yaml
memolink:
  notes-dir: ~/notes          # default; override in application.yml
```

#### REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/graph` | Full graph JSON: `{nodes, edges}` |
| `GET` | `/api/search?q=…&limit=20` | Lucene search; returns `[{id, title, score}, …]` |
| `GET` | `/api/notes/{id}` | Structured note: `{id, title, tags, headings, wikiLinks, body}` |
| `GET` | `/api/traverse/{id}?depth=2&maxNeighbors=5&minWeight=3` | BFS result: `["kafka.md", "java.md", …]` |

#### Graph JSON schema

```json
{
  "nodes": [
    { "id": "spring-boot.md", "label": "Spring Boot" }
  ],
  "edges": [
    {
      "source": "spring-boot.md",
      "target": "kafka.md",
      "weight": 7,
      "reasons": ["wiki_link", "shared_tags"]
    }
  ]
}
```

#### Cytoscape.js UI features

- Dark theme (Obsidian-inspired).
- Force-directed (`cose`) layout with switchable alternatives: circle, grid, concentric.
- Click a node → right panel shows title, content, tags, headings, outbound links, backlinks.
- Search bar → Lucene-powered, highlights matched nodes and dims others in real time.
- Zoom / pan / fit controls.

---

### `memolink-spring-ai-starter`

**Package:** `ist.logic.ai`  
**Type:** Spring Boot auto-configuration starter.

#### What it provides

- `MemoLinkAiAutoConfiguration` — registers the beans below.
- Same `KnowledgeGraph`, `GraphSearchService`, `GraphTraversalService`, `GraphWatchService` beans as the viewer starter (independently; can coexist if the consuming app uses both).
- `MemoLinkAiTools` bean — a plain class (not a Spring component in source) with four `@Tool`-annotated methods.

The auto-configuration is `@ConditionalOnClass(Tool.class)` — it is a no-op if Spring AI is not on the classpath, making the starter safe to add to any project.

#### `MemoLinkAiTools` methods

| Method | Return type | Description |
|--------|-------------|-------------|
| `searchMdFiles(String query)` | `List<SearchResult>` | Lucene search; up to 10 hits ranked by relevance, each with `id`, `title`, `score` |
| `getRelatedMdFiles(String fileId)` | `List<String>` | Graph traversal (depth=2, max 5 neighbors, min weight 3) |
| `getMdFileContent(String fileId)` | `NoteDetail` | Structured note: `id`, `title`, `tags`, `headings`, `wikiLinks`, `body` (clean prose) |
| `traverseGraph(String fileId, int depth)` | `List<String>` | BFS traversal up to the given depth (capped at 3) |

#### Recommended agent flow

```
1. searchMdFiles(userQuery)         → ranked hits; check scores to pick entry points
2. getRelatedMdFiles(topResult.id)  → expand to connected notes
3. getMdFileContent(relevantId)     → load structured content for LLM context
```

This is lightweight Graph RAG — the graph reduces how much text is loaded into the context window.

#### Usage in a ChatClient

```java
chatClient.prompt()
    .user(question)
    .tools(memoLinkAiTools)   // injected as a Spring bean
    .call()
    .content();
```

---

### `memolink-mcp-server`

**Package:** `ist.logic.mcp`  
**Type:** Runnable Spring Boot application (has `spring-boot-maven-plugin`).

Exposes the knowledge graph as an MCP server using the stdio transport, making it compatible with Claude Desktop, Cursor, and any other MCP-compliant client.

#### MCP tools

| Tool name | Return type | Description |
|-----------|-------------|-------------|
| `search_md_files` | `List<SearchResult>` | Lucene search; returns ranked hits with `id`, `title`, `score` |
| `get_related_md_files` | `List<String>` | Graph traversal from a file |
| `get_md_file` | `NoteDetail` | Structured note content |
| `traverse_graph` | `List<String>` | BFS traversal with configurable depth |

#### Configuration

```yaml
# application.yml (baked into the JAR)
memolink:
  notes-dir: ${MEMOLINK_NOTES_DIR:${user.home}/notes}

spring:
  main:
    web-application-type: none   # no HTTP server — pure stdio MCP
  ai:
    mcp:
      server:
        type: SYNC               # stdio; use ASYNC for SSE
```

The console logging pattern is suppressed (`pattern.console: ""`) so the stdio channel stays clean for the MCP JSON-RPC protocol.

#### Claude Desktop integration

```json
{
  "mcpServers": {
    "memolink": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/memolink-mcp-server-0.1.0-SNAPSHOT.jar"],
      "env": {
        "MEMOLINK_NOTES_DIR": "/Users/yourname/notes"
      }
    }
  }
}
```

---

## Design Decisions

### Why no Spring in `memolink-core`?

Keeping the core framework-free means:
- It can be used in any JVM project, not just Spring apps.
- It is trivial to unit-test — no Spring context needed.
- The starter modules are thin wrappers that simply wire core services as Spring beans.

### Why in-memory graph and index?

For the V1 use case (personal/team notes, typically hundreds to a few thousand files) an in-memory `HashMap` + Lucene `ByteBuffersDirectory` is:
- Fast to build (sub-second for 1 000 files).
- Zero infrastructure — no database, no external service.
- Live-updated incrementally — only changed files are re-parsed on each save.

### Why not embeddings / vector search?

The relationship signals (wiki-links, tags, keywords) already encode the *author's* intent — they are stronger signals than cosine similarity of embeddings for this use case. Embeddings are explicitly deferred to V2.

### Why two starters instead of one?

`memolink-viewer-starter` requires Spring MVC (web). `memolink-spring-ai-starter` does not. Separating them means an MCP server or a headless AI service does not pull in `spring-boot-starter-web`.

### `-parameters` compiler flag

The parent POM sets `-parameters` in `maven-compiler-plugin`. This is required by Spring 6 / Spring Boot 3 to resolve `@RequestParam` and `@PathVariable` names at runtime via reflection. Spring Boot's parent POM sets this automatically, but this project imports the Spring Boot BOM rather than extending the parent, so the flag must be set explicitly.

---

## Build & Test

```bash
# Compile + run all tests
mvn clean test

# Build all JARs
mvn clean install -DskipTests

# Run viewer example
mvn spring-boot:run -pl examples/viewer-app

# Run AI chat example
OPENAI_API_KEY=sk-... mvn spring-boot:run -pl examples/spring-ai-app

# Build MCP server fat JAR
mvn -pl memolink-mcp-server package -am -DskipTests
```

Tests live in `memolink-core` (`GraphBuilderServiceTest`) and cover scanning, parsing, relationship scoring, and traversal.

---

## Dependency Versions

| Dependency | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.4.5 |
| Spring AI | 1.0.0 |
| flexmark-java | 0.64.8 |
| Apache Lucene | 9.12.0 |
| Cytoscape.js | 3.29.2 |
| Maven | 3.9+ |

---

## What Is Not Built (V1 Scope)

| Feature | Reason deferred |
|---------|----------------|
| Embeddings / vector search | Adds infrastructure; keyword signals sufficient for V1 |
| Neo4j / graph database | In-memory sufficient at personal-notes scale |
| Authentication | Left to the consuming application |
| Semantic similarity | Deferred to V2 with embedding support |
