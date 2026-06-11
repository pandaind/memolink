# MemoLink MCP Server — Usage Guide

---

## Contents

1. [Generate API keys](#1-generate-api-keys)
2. [Mode 1 — stdio (local, no auth)](#2-mode-1--stdio-local-no-auth)
3. [Mode 2 — HTTP/SSE (remote, API key auth)](#3-mode-2--httpsse-remote-api-key-auth)
4. [VS Code configuration](#4-vs-code-configuration)
5. [Claude Desktop configuration](#5-claude-desktop-configuration)
6. [Cursor configuration](#6-cursor-configuration)
7. [Available tools](#7-available-tools)
8. [Available prompts](#8-available-prompts)
9. [Role reference](#9-role-reference)

---

## 1. Generate API keys

API keys are only needed for **HTTP/SSE mode**. In stdio mode the OS process model is the trust boundary.

### Option A — openssl (recommended)

```bash
# Generate a cryptographically random 32-byte key, base64-encoded
openssl rand -base64 32

# Example output:
# 4K7mXp2Nv8QzRtY1aLbWcJfHeUdGsOiP3nBxZqAkVwE=

# Prefix with a recognisable label so you know what it's for
echo "sk-vscode-$(openssl rand -base64 24 | tr -d '=+/')"
echo "sk-claude-$(openssl rand -base64 24 | tr -d '=+/')"
echo "sk-ci-$(openssl rand -base64 24 | tr -d '=+/')"
```

### Option B — uuidgen

```bash
echo "sk-vscode-$(uuidgen | tr -d '-' | tr '[:upper:]' '[:lower:]')"
echo "sk-claude-$(uuidgen | tr -d '-' | tr '[:upper:]' '[:lower:]')"
```

### Option C — Python one-liner

```bash
python3 -c "import secrets; print('sk-vscode-' + secrets.token_urlsafe(32))"
python3 -c "import secrets; print('sk-claude-' + secrets.token_urlsafe(32))"
python3 -c "import secrets; print('sk-ci-'     + secrets.token_urlsafe(32))"
```

### Store keys in a `.env` file (never commit this)

Create `~/.memolink/.env`:

```bash
mkdir -p ~/.memolink
cat > ~/.memolink/.env << 'EOF'
MEMOLINK_NOTES_DIR=/Users/your-name/notes
MEMOLINK_HTTP_PORT=8765
MEMOLINK_KEY_VSCODE=sk-vscode-REPLACE_ME
MEMOLINK_KEY_CLAUDE=sk-claude-REPLACE_ME
MEMOLINK_KEY_CI=sk-ci-REPLACE_ME
EOF
chmod 600 ~/.memolink/.env
```

Then fill in the generated keys:

```bash
# Replace placeholders with actual generated values
sed -i '' "s/sk-vscode-REPLACE_ME/sk-vscode-$(openssl rand -base64 24 | tr -d '=+/')/" ~/.memolink/.env
sed -i '' "s/sk-claude-REPLACE_ME/sk-claude-$(openssl rand -base64 24 | tr -d '=+/')/" ~/.memolink/.env
sed -i '' "s/sk-ci-REPLACE_ME/sk-ci-$(openssl rand -base64 24 | tr -d '=+/')/" ~/.memolink/.env
```

View your keys:

```bash
cat ~/.memolink/.env
```

---

## 2. Mode 1 — stdio (local, no auth)

The MCP client spawns the server as a child process over stdin/stdout.  
**No API key required.** Trust is provided by OS process ownership.

### Build the jar

```bash
cd /path/to/memolink
mvn package -pl memolink-mcp-server -am -DskipTests -q
```

### Run manually (for testing)

```bash
MEMOLINK_NOTES_DIR=~/notes \
  java --enable-native-access=ALL-UNNAMED \
       -jar memolink-mcp-server/target/memolink-mcp-server-0.1.0-SNAPSHOT.jar
```

The server reads JSON-RPC from stdin and writes responses to stdout.  
On first start it extracts the ONNX model to `~/.memolink/models/` (~86 MB, one-time only).

### Optional Headroom Compression
Memolink supports an optional `headroom` compression sidecar to reduce token limits. Set `HEADROOM_ENABLED=false` in your `.env` to disable it if you do not want to use compression or run the sidecar.

---

## 3. Mode 2 — HTTP/SSE (remote, API key auth)

Runs as a standard Spring Boot web app. Each MCP client connects over HTTP; the SSE endpoint streams server events.

### Start with the `http` profile

```bash
# Load keys from .env file, then start
source ~/.memolink/.env

java --enable-native-access=ALL-UNNAMED \
     -jar memolink-mcp-server/target/memolink-mcp-server-0.1.0-SNAPSHOT.jar \
     --spring.profiles.active=http
```

Or pass everything inline:

```bash
MEMOLINK_NOTES_DIR=~/notes \
MEMOLINK_HTTP_PORT=8765 \
MEMOLINK_KEY_VSCODE=sk-vscode-abc123 \
MEMOLINK_KEY_CLAUDE=sk-claude-xyz789 \
  java --enable-native-access=ALL-UNNAMED \
       -jar memolink-mcp-server/target/memolink-mcp-server-0.1.0-SNAPSHOT.jar \
       --spring.profiles.active=http
```

Server starts at `http://localhost:8765`.

### Test the connection

```bash
# Should return 200 with server capabilities
curl -s http://localhost:8765/mcp/sse \
     -H "X-API-Key: sk-vscode-abc123" \
     -H "Accept: text/event-stream" &

# Or with Bearer token
curl -s http://localhost:8765/mcp/sse \
     -H "Authorization: Bearer sk-vscode-abc123" \
     -H "Accept: text/event-stream" &
```

Missing or invalid key returns:
```json
{"error": "Unauthorized", "message": "Missing API key. Provide X-API-Key header or Authorization: Bearer <key>."}
```

---

## 4. VS Code configuration

### stdio mode (`.vscode/mcp.json`)

```json
{
  "servers": {
    "memolink": {
      "type": "stdio",
      "command": "/path/to/java",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
        "/path/to/memolink-mcp-server-0.1.0-SNAPSHOT.jar"
      ],
      "env": {
        "MEMOLINK_NOTES_DIR": "/Users/your-name/notes"
      }
    }
  }
}
```

Find your `java` path:
```bash
which java
# or for mise-managed java:
/Users/your-name/.local/share/mise/shims/java
```

### HTTP/SSE mode (`.vscode/mcp.json`)

```json
{
  "servers": {
    "memolink-http": {
      "type": "sse",
      "url": "http://localhost:8765/mcp/sse",
      "headers": {
        "X-API-Key": "sk-vscode-abc123"
      }
    }
  }
}
```

> **Tip:** Use the HTTP mode when sharing a single server instance across multiple machines or team members.

---

## 5. Claude Desktop configuration

File: `~/Library/Application Support/Claude/claude_desktop_config.json`

### stdio mode

```json
{
  "mcpServers": {
    "memolink": {
      "command": "/path/to/java",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
        "/path/to/memolink-mcp-server-0.1.0-SNAPSHOT.jar"
      ],
      "env": {
        "MEMOLINK_NOTES_DIR": "/Users/your-name/notes"
      }
    }
  }
}
```

### HTTP mode (read-only client)

Claude Desktop does not yet support `X-API-Key` headers natively, so run a local proxy or use an SSE-compatible MCP bridge. Alternatively, give Claude Desktop its own stdio server instance pointed at the same notes directory.

---

## 6. Cursor configuration

File: `~/.cursor/mcp.json` (or workspace `.cursor/mcp.json`)

### stdio mode

```json
{
  "mcpServers": {
    "memolink": {
      "command": "/path/to/java",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
        "/path/to/memolink-mcp-server-0.1.0-SNAPSHOT.jar"
      ],
      "env": {
        "MEMOLINK_NOTES_DIR": "/Users/your-name/notes"
      }
    }
  }
}
```

### HTTP/SSE mode

```json
{
  "mcpServers": {
    "memolink-http": {
      "url": "http://localhost:8765/mcp/sse",
      "headers": {
        "X-API-Key": "sk-cursor-abc123"
      }
    }
  }
}
```

---

## 7. Available tools

| Tool | Role needed | Description |
|---|---|---|
| `list_md_files` | READ | List all note IDs |
| `get_md_file` | READ | Read a note's content, tags, wiki-links |
| `search_md_files` | READ | Semantic vector search (falls back to BM25) |
| `semantic_search` | READ | Pure vector search (embeddings) |
| `get_related_md_files` | READ | BFS graph neighbours |
| `get_graph_context` | READ | Note + 1-hop neighbours with relationship types |
| `traverse_graph` | READ | Multi-hop BFS traversal |
| `find_path_between_notes` | READ | Shortest path between two notes |
| `get_memory_summary` | READ | Graph stats, top tags, hub notes, importance list |
| `gather_reflection_sources` | READ | Collect source notes for a reflection/summary |
| `create_md_file` | WRITE | Create a new note |
| `update_md_file` | WRITE | Update an existing note |
| `delete_md_file` | WRITE | Delete a note |
| `set_note_importance` | WRITE | Set note importance (0–10), persisted to frontmatter |

---

## 8. Available prompts

| Prompt | Arguments | What it does |
|---|---|---|
| `list_notes` | — | Browse all notes grouped by topic |
| `search_notes` | `query` | Keyword search + related notes |
| `semantic_search` | `query` | Vector search + graph context |
| `find_path` | `from_id`, `to_id` | Narrate connection between two notes |
| `create_note` | `title`, `body`, `tags`, `links` | Full create workflow |
| `update_note` | `file_id`, `instructions` | Read-then-update workflow |
| `delete_note` | `file_id` | Safe delete with dependency check |
| `generate_reflection` | `topic`, `max_sources` | Synthesise a summary note |
| `memory_overview` | — | Full graph stats report |

---

## 9. Role reference

| Role | HTTP verb | Endpoints | Operations |
|---|---|---|---|
| `READ` | `GET` | `/mcp/**` | All search, list, get, traverse tools |
| `WRITE` | `POST` | `/mcp/**` | Create, update, delete, set_importance, reflection |

Configure roles per client in `~/.memolink/.env` and `application-http.yml`:

```yaml
memolink:
  auth:
    enabled: true
    clients:
      - name: vscode        # full access
        key: ${MEMOLINK_KEY_VSCODE}
        roles: [READ, WRITE]
      - name: claude-desktop  # read-only
        key: ${MEMOLINK_KEY_CLAUDE}
        roles: [READ]
      - name: ci-bot          # read-only automation
        key: ${MEMOLINK_KEY_CI}
        roles: [READ]
```

---

## Quick reference

```bash
# Generate all keys at once and write to .env
mkdir -p ~/.memolink
printf "MEMOLINK_NOTES_DIR=%s\n" "$HOME/notes" > ~/.memolink/.env
printf "MEMOLINK_HTTP_PORT=8765\n" >> ~/.memolink/.env
printf "MEMOLINK_KEY_VSCODE=sk-vscode-%s\n"  "$(openssl rand -base64 24 | tr -d '=+/')" >> ~/.memolink/.env
printf "MEMOLINK_KEY_CLAUDE=sk-claude-%s\n"  "$(openssl rand -base64 24 | tr -d '=+/')" >> ~/.memolink/.env
printf "MEMOLINK_KEY_CI=sk-ci-%s\n"          "$(openssl rand -base64 24 | tr -d '=+/')" >> ~/.memolink/.env
chmod 600 ~/.memolink/.env
echo "Keys written to ~/.memolink/.env"
cat ~/.memolink/.env
```
