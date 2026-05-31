# MemoLink

> Transform markdown notes into an AI-navigable knowledge graph with Obsidian-like visualization.

MemoLink scans a folder of `.md` files, discovers relationships between them (via wiki-links, shared tags, and shared keywords), and exposes the result as:

- an **interactive Cytoscape.js graph viewer** (Spring Boot starter)
- **Spring AI tools** so any LLM agent can search and traverse your notes (Spring Boot starter)
- a **Model Context Protocol (MCP) server** for Claude Desktop, Cursor, and other MCP clients

---

## Modules

| Module | Type | Purpose |
|--------|------|---------|
| `memolink-core` | Pure Java library | Scan → parse → score → graph |
| `memolink-viewer-starter` | Spring Boot auto-config starter | Cytoscape.js UI + REST API |
| `memolink-spring-ai-starter` | Spring Boot auto-config starter | `@Tool` methods for AI agents |
| `memolink-mcp-server` | Runnable Spring Boot app | MCP stdio server |

---

## Quick Start

### 1 — Graph Viewer

Add one dependency to any Spring Boot web application:

```xml
<dependency>
    <groupId>ist.logic</groupId>
    <artifactId>memolink-viewer-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure your notes directory in `application.yml`:

```yaml
memolink:
  notes-dir: /path/to/your/notes
```

Run the app and open [http://localhost:8080](http://localhost:8080).  
The graph loads automatically. Click any node to see its content, tags, and backlinks. Use the search bar to filter. Changes to `.md` files are picked up automatically without a restart.

---

### 2 — Spring AI Tools

Add the AI starter alongside an OpenAI (or other provider) starter:

```xml
<dependency>
    <groupId>ist.logic</groupId>
    <artifactId>memolink-spring-ai-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

Inject `MemoLinkAiTools` and pass it to your `ChatClient`:

```java
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final MemoLinkAiTools memoLinkAiTools;

    public ChatController(ChatClient.Builder builder, MemoLinkAiTools memoLinkAiTools) {
        this.chatClient      = builder.build();
        this.memoLinkAiTools = memoLinkAiTools;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .tools(memoLinkAiTools)
                .call()
                .content();
    }
}
```

The LLM can now autonomously call `searchMdFiles`, `getRelatedMdFiles`, `getMdFileContent`, and `traverseGraph` to answer questions grounded in your notes.

---

### 3 — MCP Server (Claude Desktop / Cursor)

Build the fat JAR:

```bash
mvn -pl memolink-mcp-server package -am
```

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "memolink": {
      "command": "java",
      "args": ["-jar", "/path/to/memolink-mcp-server-0.1.0-SNAPSHOT.jar"],
      "env": {
        "MEMOLINK_NOTES_DIR": "/path/to/your/notes"
      }
    }
  }
}
```

Restart Claude Desktop. It will now have access to your knowledge graph through four MCP tools: `search_md_files`, `get_related_md_files`, `get_md_file`, and `traverse_graph`.

---

## REST API (viewer starter)

| Endpoint | Description |
|----------|-------------|
| `GET /api/graph` | Full graph JSON (`nodes` + `edges`) |
| `GET /api/search?q={query}` | Lucene full-text search; returns `[{id, title, score}]` |
| `GET /api/notes/{id}` | Structured note: `{id, title, tags, headings, wikiLinks, body}` |
| `GET /api/traverse/{id}?depth=2` | BFS traversal from a node; returns connected file IDs |

---

## AI Tool Return Types

Search and content tools return structured data, not raw text:

```java
// search returns ranked hits with score and title
record SearchResult(String id, String title, float score)

// content returns clean structured fields — no markdown syntax noise
record NoteDetail(String id, String title, List<String> tags,
                  List<String> headings, List<String> wikiLinks, String body)
```

An agent can check scores to decide which notes are worth loading — avoiding unnecessary round trips.

---

## Relationship Scoring

MemoLink infers edges from three signals — no manual linking required beyond wiki-links:

| Signal | Score | Cap |
|--------|-------|-----|
| Wiki-link (`[[other-file]]`) | +5 | — |
| Each shared tag | +2 | 3 tags → max +6 |
| Each shared keyword | +1 | 5 keywords → max +5 |

Edges are built using an inverted index — only pairs that share at least one signal are ever scored (O(n·k) instead of O(n²)).

---

## Real-Time File Watching

All starters watch `memolink.notes-dir` for `.md` file changes. When a file is saved, added, or deleted:

1. Changed paths are accumulated during a 500 ms debounce window.
2. Only the changed files are re-parsed; unchanged notes are carried over.
3. The graph and Lucene index are swapped atomically (old index closed to free memory).

No restart needed.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Build | Java 21, Maven 3.9, Spring Boot 3.4.5 |
| Parsing | flexmark-java 0.64.8 |
| Search | Apache Lucene 9.12.0 |
| Visualization | Cytoscape.js 3.29.2 |
| AI Tools | Spring AI 1.0.0 |
| MCP | Spring AI MCP Server |

---

## Running the Examples

```bash
# Build everything first
mvn clean install -DskipTests

# Graph viewer (http://localhost:8080)
mvn spring-boot:run -pl examples/viewer-app

# Spring AI chat (http://localhost:8081/chat?message=...)
OPENAI_API_KEY=sk-... mvn spring-boot:run -pl examples/spring-ai-app
```

Both examples point at `examples/sample-notes/` which contains five interlinked markdown files (`spring-boot.md`, `spring-framework.md`, `java.md`, `kafka.md`, `spring-ai.md`).

---

## Project Structure

```
memolink/                            # root project (artifact: memolink)
├── memolink-core/                  # Pure Java — no Spring dependency
├── memolink-viewer-starter/        # Auto-config starter: viewer UI + REST API
├── memolink-spring-ai-starter/     # Auto-config starter: Spring AI @Tool methods
├── memolink-mcp-server/            # Runnable MCP server application
└── examples/
    ├── sample-notes/               # Five interlinked .md files to try immediately
    ├── viewer-app/                 # Minimal viewer-starter consumer app
    └── spring-ai-app/              # Minimal spring-ai-starter consumer app
```

---

## License

MIT
