# MemoLink — Complete Final Plan

## One-Line Vision

```text id="5jlwmg"
MemoLink — Transform markdown notes into an AI-navigable knowledge graph with Obsidian-like visualization.
```

---

# Core Intent

Build a lightweight system that:

* scans markdown notes
* discovers relationships between notes
* visualizes connections like Obsidian
* exposes intelligent retrieval tools for AI agents

The focus is:

```text id="2jlwmm"
Simple + Powerful + AI-ready
```

NOT enterprise complexity.

---

# Core Philosophy

## Nodes

ONLY markdown files are nodes.

Example:

```text id="4jlwmt"
spring.md
kafka.md
redis.md
```

---

# Relationships Are Created Using

| Signal          | Purpose                |
| --------------- | ---------------------- |
| Wiki links      | explicit relationships |
| Shared tags     | topic grouping         |
| Shared keywords | similarity             |
| Shared headings | contextual similarity  |

These are NOT nodes.

They are only signals used to connect notes.

---

# Final Architecture

```text id="5jlwmy"
Markdown Files
      ↓
memolink-core
      ↓
Knowledge Graph
      ↓
├── Viewer Starter
│
└── AI Tool Layer
```

---

# MODULES

# 1. `memolink-core`

MOST IMPORTANT MODULE.

Pure Java library.

Keep it framework-independent.

---

# Responsibilities

## A. Scan Markdown Folder

Example:

```text id="6jlwme"
notes/**/*.md
```

---

## B. Parse Markdown

Use:

* [flexmark-java](https://github.com/vsch/flexmark-java?utm_source=chatgpt.com)

Extract:

* wiki links
* tags
* headings
* content
* keywords

---

## C. Create Note Metadata

Example:

```java id="9jlwmb"
class NoteMetadata {

    String fileName;

    Set<String> wikiLinks;

    Set<String> tags;

    Set<String> keywords;

}
```

---

## D. Build Relationships

Relationship scoring:

| Signal         | Weight |
| -------------- | ------ |
| Wiki link      | +5     |
| Shared tag     | +2     |
| Shared keyword | +1     |

Example:

```text id="7jlwms"
spring.md ↔ kafka.md
```

---

## E. Generate Graph JSON

Example:

```json id="5jlwmg"
{
  "nodes": [],
  "edges": []
}
```

---

# Graph Schema

## Node

```json id="2jlwmm"
{
  "id": "spring.md",
  "label": "Spring"
}
```

---

## Edge

```json id="4jlwmt"
{
  "source": "spring.md",
  "target": "kafka.md",
  "weight": 8,
  "reasons": [
    "wiki_link",
    "shared_tags"
  ]
}
```

---

# 2. Search Engine

Use:

* [Apache Lucene](https://lucene.apache.org/?utm_source=chatgpt.com)

Lucene indexes MARKDOWN CONTENT.

NOT graph JSON.

---

# Lucene Indexes

| Field    | Example                           |
| -------- | --------------------------------- |
| title    | spring                            |
| content  | Spring Boot integrates with Kafka |
| tags     | backend                           |
| headings | Spring Boot                       |

---

# Purpose of Lucene

```text id="5jlwmy"
Fast text relevance search
```

---

# Purpose of Graph

```text id="6jlwme"
Relationship-aware retrieval
```

Together:

```text id="9jlwmb"
Hybrid Retrieval
```

---

# Retrieval Flow

```text id="7jlwms"
User Query
      ↓
Lucene Search
      ↓
Top Matching Notes
      ↓
Graph Expansion
      ↓
Related Notes
      ↓
Load Relevant Markdown
      ↓
LLM
```

This keeps retrieval lightweight.

---

# IMPORTANT PERFORMANCE RULE

Do NOT load all markdown files.

ONLY load:

* top search results
* nearby graph neighbors

The graph REDUCES retrieval size.

---

# Recommended Retrieval Limits

| Setting         | Value |
| --------------- | ----- |
| max graph depth | 1–2   |
| max neighbors   | 5     |
| min edge weight | 3     |

Simple and effective.

---

# Core Services

```java id="5jlwmy"
NoteScannerService
NoteParserService
RelationshipEngine
GraphBuilderService
GraphSearchService
GraphTraversalService
```

---

# 2. `memolink-viewer-starter`

Purpose:

```text id="6jlwme"
Obsidian-like graph visualization
```

---

# Visualization

Use:

* [Cytoscape.js](https://js.cytoscape.org/?utm_source=chatgpt.com)

---

# Features

## V1 Features

✅ graph rendering
✅ zoom/pan
✅ search notes
✅ highlight neighbors
✅ click note
✅ backlinks

---

# Viewer Flow

```text id="9jlwmb"
Graph JSON
      ↓
Cytoscape.js
      ↓
Interactive Graph UI
```

---

# 3. `memolink-spring-ai-starter`

Purpose:

```text id="7jlwms"
Expose graph capabilities as AI tools
```

Use:

* [Spring AI](https://spring.io/projects/spring-ai?utm_source=chatgpt.com)

---

# AI Tools

## Search Notes

```java id="5jlwmg"
@Tool
List<String> searchNotes(String query)
```

---

## Related Notes

```java id="2jlwmm"
@Tool
List<String> getRelatedNotes(String note)
```

---

## Get Note Content

```java id="4jlwmt"
@Tool
String getNoteContent(String note)
```

---

## Traverse Graph

```java id="5jlwmy"
@Tool
List<String> traverse(String note, int depth)
```

---

# AI Agent Flow

```text id="6jlwme"
User Question
      ↓
Agent Uses Tools
      ↓
Search Notes
      ↓
Traverse Relationships
      ↓
Load Relevant Notes
      ↓
LLM
```

This is lightweight Graph RAG.

---

# 4. `memolink-mcp-server`

Purpose:

```text id="9jlwmb"
Expose graph tools through MCP
```

Supports:

* Claude Desktop
* Cursor
* future AI agents

---

# MCP Tools

```text id="7jlwms"
search_notes
get_related_notes
get_note
traverse_graph
```

---

# DEVELOPMENT PHASES

# Phase 1 — Core

Build:

✅ markdown scanning
✅ wiki links
✅ tags
✅ keywords
✅ relationship scoring
✅ graph JSON

NO UI yet.

---

# Phase 2 — Viewer

Build:

✅ Cytoscape visualization
✅ note search
✅ neighbor highlighting
✅ backlinks

Obsidian-like graph.

---

# Phase 3 — Search

Build:

✅ Lucene indexing
✅ hybrid retrieval
✅ graph traversal

---

# Phase 4 — Spring AI

Build:

✅ AI tools
✅ agent retrieval flow

---

# Phase 5 — MCP

Build:

✅ MCP server
✅ external AI interoperability

---

# DO NOT BUILD INITIALLY

❌ vector DB
❌ Neo4j
❌ distributed systems
❌ realtime sync

Keep V1 clean and small.

---

# Recommended Tech Stack

| Layer         | Tech                                                                          |
| ------------- | ----------------------------------------------------------------------------- |
| Backend       | Spring Boot                                                                   |
| Parsing       | [flexmark-java](https://github.com/vsch/flexmark-java?utm_source=chatgpt.com) |
| Search        | [Apache Lucene](https://lucene.apache.org/?utm_source=chatgpt.com)            |
| Visualization | [Cytoscape.js](https://js.cytoscape.org/?utm_source=chatgpt.com)              |
| AI Tools      | [Spring AI](https://spring.io/projects/spring-ai?utm_source=chatgpt.com)      |
| AI Protocol   | MCP                                                                           |

---

# Final System Intent

```text id="5jlwmg"
A lightweight markdown knowledge graph engine that helps both humans and AI agents navigate connected knowledge intelligently.
```

