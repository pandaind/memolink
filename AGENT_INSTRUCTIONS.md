# MemoLink MCP Agent Instructions

Welcome! This document provides instructions for AI Agents using the MemoLink Model Context Protocol (MCP) server. MemoLink is a knowledge-graph-backed markdown vault.

## Available Tools & Recommended Workflows

### 1. Orientation & Discovery
Before modifying the vault or performing extensive research, gain context on the repository structure.
- **`get_memory_summary`**: Provides the total number of notes, top tags, most connected notes, and notes by importance.
- **`list_md_files`**: Lists all available markdown files in the vault. 

### 2. Searching
MemoLink defaults to **semantic search** to capture conceptually related context, falling back to BM25 keyword search if the semantic engine is unavailable.
- **`search_md_files`**: The primary search tool. Always start your queries here. It returns ranked results based on conceptual similarity.
- **`semantic_search`**: A direct interface for the semantic engine (returns identical results to `search_md_files` by default).

### 3. Reading & Graph Context
When reading a note, you often want to know what it is connected to.
- **`get_md_file`**: Fetches the structured content of a single note. If the headroom compression sidecar is enabled, the body text may be compressed for efficiency.
- **`get_graph_context`**: Fetches a note along with its 1-hop neighborhood. Use this for GraphRAG-style context gathering.
- **`traverse_graph`** & **`get_related_md_files`**: Use these to explore the interconnected knowledge structure starting from a focal note.

### 4. Writing & Managing
When instructed to add or modify information:
- **`create_md_file`**: Creates a new note. The tool will automatically normalize the filename and discover related links in the background.
- **`update_md_file`**: Updates an existing note. Ensure you use `get_md_file` first to preserve existing frontmatter metadata and sections if necessary.
- **`gather_reflection_sources`**: Helps you synthesize a summary note across multiple sources by pulling excerpts.
- **`set_note_importance`**: Increases a note's baseline ranking in subsequent searches. Use this for foundational architectural or conceptual notes.

## Best Practices
- **Do NOT manually crawl directories.** Always use the MCP tools to interact with the notes to keep the knowledge graph in sync and to benefit from semantic indexing.
- Avoid passing massive raw texts; if the `headroom` compression sidecar is active, rely on it to reduce context window usage.
