# MemoLink

> An AI-native knowledge graph engine for your markdown notes.

MemoLink scans your local `.md` files, maps the relationships between them using wiki-links, tags, and semantic concepts, and exposes this interconnected vault to:

- **AI Agents** via a Model Context Protocol (MCP) server (e.g., Claude Desktop, Cursor) and Spring AI tools.
- **Humans** via an interactive web-based graph viewer.

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

Configure your vault directory in `application.yml`:

```yaml
memolink:
  vault-dir: /path/to/your/vault
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

The LLM can now autonomously call `searchMdFiles`, `getRelatedMdFiles`, `getMdFileContent`, and `traverseGraph` to answer questions grounded in your vault.

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
         "MEMOLINK_VAULT_DIR": "/path/to/your/vault"
       }
     }
   }
 }
 ```
 
 Restart Claude Desktop. It will now have access to your knowledge graph through the MCP tools: `search_md_files` (which defaults to semantic search), `get_related_md_files`, `get_md_file`, `traverse_graph`, and more.

 For comprehensive guidelines on how an AI should use these tools, please refer to the [Agent Instructions](AGENT_INSTRUCTIONS.md).

 #### 100% Local Semantic Embeddings

 Memolink features built-in semantic vector search. It runs the `all-MiniLM-L6-v2` ONNX model entirely locally inside the Java process. Your vault's content is never sent to external APIs for embedding generation, ensuring complete privacy, zero API costs, and lightning-fast indexing.

 #### Headroom Compression Sidecar

 Memolink utilizes an optional Python FastAPI sidecar (Headroom) running a `kompress-small` ONNX model to compress markdown note bodies before sending them to the LLM to save token limits. 
 
 - **Disable Compression:** Set `HEADROOM_ENABLED=false` in `.env`.
 - **Docker Compose Setup:** The headroom sidecar requires the `compression` profile. To run Memolink natively without compression, you can just run `docker compose up memolink`. If you wish to use compression, run `docker compose --profile compression up`.

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

All starters watch `memolink.vault-dir` for `.md` file changes. When a file is saved, added, or deleted:

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
