# MemoLink MCP Agent Instructions

You are an AI Agent interacting with **MemoLink**, a Knowledge-Graph-backed markdown vault. 
To ensure **100% accuracy and optimal context retrieval**, you MUST strictly follow the workflows and rules outlined in this document. 

---

## 🚨 MANDATORY RULES FOR ALL AGENTS
1. **NEVER manually crawl or read the filesystem directories.** You must exclusively use the provided MCP tools to interact with notes. Bypassing the MCP tools breaks the semantic indexing and graph relationships.
2. **TRUST THE COMPRESSION:** If the `headroom` compression sidecar is enabled, the text you receive from tools like `get_md_file` will be compressed (filler words removed). Do not assume data is missing; the core semantic meaning and technical facts are preserved to save your context window limits.
3. **PAGINATE YOUR RESEARCH:** Do not attempt to load 10 notes at once. Read the search scores, and fetch the content of only the top 1 or 2 most relevant notes.

---

## 🔄 STANDARD OPERATING FLOWS

Follow these exact sequences depending on the user's request.

### FLOW A: General Research & Question Answering
*Use this flow when the user asks a question about the vault's contents.*

1. **Step 1: Broad Search**
   - Call `search_md_files(query)` with the user's core concepts. 
   - *Note: This uses an 80% Semantic / 20% Keyword hybrid model. It will find conceptually related notes even if exact words don't match.*
2. **Step 2: Evaluate Relevance**
   - Review the `score` and `title` of the returned hits. 
   - Identify the top 1-2 most relevant `file_id`s. Ignore results with scores below 0.3.
3. **Step 3: Fetch Graph Context**
   - Call `get_graph_context(file_id)` on the best hit. 
   - *Why?* This returns the note's structured content PLUS its immediate 1-hop graph neighbors. This gives you a complete "GraphRAG" view without needing multiple tool calls.
4. **Step 4: Answer the User**
   - Formulate your answer using the compressed body text and neighbor context.

### FLOW B: Creating a New Note
*Use this flow when asked to document something new.*

1. **Step 1: Check for Duplicates**
   - Call `search_md_files(title_or_concept)` to ensure a similar note does not already exist. If it does, switch to **FLOW C**.
2. **Step 2: Create the File**
   - Call `create_md_file(file_id, title, body, wiki_links, tags, metadata)`.
   - *Agent Behavior:* Memolink will automatically normalize your filename and auto-discover related wiki-links in the background. Provide accurate `#tags` without the hash symbol.
3. **Step 3 (Optional): Set Importance**
   - If this is a foundational or architectural note, call `set_note_importance(file_id, 10)` to permanently boost it in future search rankings.

### FLOW C: Updating an Existing Note
*Use this flow to modify existing documentation.*

1. **Step 1: Read the Current State**
   - Call `get_md_file(file_id)` to retrieve the current title, tags, wiki_links, metadata, and body.
2. **Step 2: Prepare the Update**
   - Modify the body or tags in your internal context. You MUST preserve any frontmatter or metadata fields you do not intend to delete.
3. **Step 3: Push the Update**
   - Call `update_md_file(...)` with the complete, newly merged data.

### FLOW D: Synthesizing a Topic (Reflection)
*Use this flow when asked to summarize a broad topic spanning multiple notes.*

1. **Step 1: Gather Sources**
   - Call `gather_reflection_sources(topic, 5)`. 
   - This returns compressed excerpts from the top 5 notes related to the topic.
2. **Step 2: Synthesize**
   - Read the excerpts and generate a comprehensive summary.
3. **Step 3: Save the Reflection**
   - Use `create_md_file(...)` to save the summary. Tag it with `reflection` and explicitly add the source `file_id`s to the `wiki_links` array so the Knowledge Graph connects them.

---

## 🛠️ TOOL CHEAT SHEET

- **Orientation:** `get_memory_summary()`, `list_md_files()`
- **Primary Search:** `search_md_files(query)` *(Hybrid: 80% Semantic, 20% Keyword)*
- **Reading:** `get_md_file(file_id)`, `get_graph_context(file_id)`
- **Graph Traversal:** `traverse_graph(file_id, depth)`, `find_path_between_notes(from, to)`
- **Writing:** `create_md_file(...)`, `update_md_file(...)`, `delete_md_file(...)`
- **Ranking:** `set_note_importance(file_id, importance)`
