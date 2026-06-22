# MemoLink MCP Agent Instructions

You are an AI Agent interacting with **MemoLink**, a Knowledge-Graph-backed markdown vault.
To ensure **100% accuracy and optimal context retrieval**, you MUST strictly follow the
workflows and rules outlined in this document.

---

## 🚨 MANDATORY RULES FOR ALL AGENTS

1. **NEVER manually crawl or read the filesystem directories.** Use exclusively the MCP tools
   provided. Bypassing them breaks semantic indexing and graph relationships.
2. **TRUST THE COMPRESSION:** If the `headroom` compression sidecar is enabled, text received
   from tools like `read_memory` will have filler words removed. Do not assume data is missing —
   core semantic meaning and technical facts are preserved to save your context window.
3. **SCORE-FILTER AGGRESSIVELY:** `ask_vault` returns a `score` per excerpt. If the score is
   below 0.4, treat the excerpt as marginally relevant and prefer higher-scoring results.
4. **PAGINATE YOUR RESEARCH:** Do not load 10 memories at once. Read scores, then fetch the
   content of only the top 1–2 most relevant memories.

---

## 🔄 STANDARD OPERATING FLOWS

Follow these exact sequences depending on the user's request.

### FLOW A: General Research & Question Answering
*Use this flow when the user asks a question about the vault's contents.*

1. **Step 1: Ask the Vault**
   - Call `ask_vault(query)` with the user's question.
   - This is the fastest path — it returns precise, compressed paragraphs ranked by semantic
     similarity score. Use the `score` field to decide which excerpts to trust.
2. **Step 2: Expand If Needed**
   - If `ask_vault` results are thin (no excerpt above score 0.5), call `search_memories(query)`
     to find the best matching full memories by title.
3. **Step 3: Fetch Graph Context**
   - Call `get_memory_context(file_id)` on the best hit.
   - *Why?* Returns the memory's full body **plus** its 1-hop graph neighbours — a complete
     GraphRAG view without multiple tool calls.
4. **Step 4: Answer the User**
   - Formulate your answer using the compressed body text and neighbour context.

### FLOW B: Creating a New Memory
*Use this flow when asked to document something new.*

1. **Step 1: Check for Duplicates**
   - Call `search_memories(title_or_concept)` to ensure a similar memory does not already exist.
     If it does, switch to **FLOW C**.
2. **Step 2: Create the Memory**
   - Call `create_memory(file_id, title, body, wiki_links, tags, metadata)`.
   - MemoLink will automatically normalise your filename and auto-discover related wiki-links
     in the background. Provide `tags` without the `#` prefix.
3. **Step 3 (Optional): Set Importance**
   - If this is a foundational or architectural memory, call
     `set_memory_importance(file_id, importance)` to permanently boost it in future search rankings.

### FLOW C: Updating an Existing Memory
*Use this flow to modify existing documentation.*

1. **Step 1: Read the Current State**
   - Call `read_memory(file_id)` to retrieve the current title, tags, wiki_links, metadata, and body.
2. **Step 2: Prepare the Update**
   - Modify the body or tags in your internal context. Preserve any frontmatter or metadata
     fields you do not intend to delete.
3. **Step 3: Push the Update**
   - Call `update_memory(...)` with the complete, newly merged data.

### FLOW D: Synthesizing a Topic (Reflection)
*Use this flow when asked to summarise a broad topic spanning multiple memories.*

1. **Step 1: Gather Sources**
   - Call `gather_sources(topic, 5)`.
   - Returns compressed excerpts from the top 5 memories related to the topic.
2. **Step 2: Synthesize**
   - Read the excerpts and generate a comprehensive summary.
3. **Step 3: Save the Reflection**
   - Use `create_memory(...)` to save the summary. Tag it with `reflection` and explicitly add
     the source `file_id`s to the `wiki_links` array so the Knowledge Graph connects them.

---

## 🛠️ TOOL CHEAT SHEET

| Category | Tool | Notes |
|---|---|---|
| **Best first** | `ask_vault(query)` | Chunk-level semantic search. Returns paragraphs + scores. Use this first for any factual query. |
| **Orientation** | `vault_summary()` | Total memories, top tags, hub memories, importance list |
| **Orientation** | `list_memories()` | All memory IDs |
| **Search** | `search_memories(query)` | Hybrid BM25 + semantic search across full memories |
| **Search** | `semantic_search(query)` | Pure vector search only |
| **Reading** | `read_memory(file_id)` | Full body, tags, headings, wiki-links |
| **Reading** | `read_memory_section(file_id, heading)` | Single section by heading |
| **Context** | `get_memory_context(file_id)` | Memory body + 1-hop GraphRAG neighbours |
| **Graph** | `get_related_memories(file_id)` | BFS neighbours depth 2 |
| **Graph** | `traverse_memories(file_id, depth)` | Multi-hop BFS up to depth 3 |
| **Graph** | `find_path(from_id, to_id)` | Shortest path between two memories |
| **Writing** | `create_memory(...)` | Create a new memory |
| **Writing** | `update_memory(...)` | Update an existing memory |
| **Writing** | `delete_memory(file_id)` | Permanently delete a memory |
| **Ranking** | `set_memory_importance(file_id, importance)` | Boost ranking (0–10) |
| **Reflection** | `gather_sources(topic, max_sources)` | Excerpts for writing a reflection memory |
