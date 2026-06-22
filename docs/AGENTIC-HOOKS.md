# MemoLink Agentic Integration & Dynamic Skills

This document explains the **How**, **What**, and **Why** of integrating MemoLink into
advanced agentic frameworks (like GitHub Copilot, Claude Desktop, or custom IDE agents)
using Pre and Post invocation hooks.

---

## 1. WHAT is this Architecture?

Instead of treating MemoLink as just a Markdown viewer, we treat the MemoLink Vault as a
**Dynamic Skill Library and Persistent Agent Memory**.

By using framework execution hooks (e.g. `hooks/pre-invoke.sh`), we intercept the agent
*before* it begins a task and inject strict, structured instructions into its system prompt.
These instructions force the agent to use the MemoLink MCP Server to retrieve relevant
skills (memories) dynamically, and write new skills back to the vault when it finishes.

## 2. WHY use Dynamic Skills via MemoLink?

1. **Token Efficiency:** Loading dozens of static `skills/*.md` files into the LLM's context
   window for every request is expensive, slow, and causes "lost in the middle" hallucinations.
2. **Contextual Accuracy:** MemoLink's chunk-level `ask_vault` tool combined with local ONNX
   embeddings ensures the agent retrieves only the exact paragraphs relevant to the current
   user prompt — not entire files.
3. **Graph-Powered Deep Dives:** Skills are stored in a Knowledge Graph. If `react-skill.md`
   links to `api-auth-skill.md`, the agent can use `get_memory_context` to read the memory
   and its 1-hop neighbours, understanding system dependencies in a single tool call.
4. **Self-Improving Agents:** Agents use `create_memory` and `update_memory` to write new
   markdown files. When an agent solves a tough bug, it saves the solution as a new skill
   memory. The next time it encounters the same bug, it dynamically discovers what it wrote.

## 3. HOW does it work?

### The Pre-Invoke Hook (`pre-invoke.sh`)
When the user asks the agent to perform a task, the framework triggers the pre-invoke hook.
The hook outputs a strict prompt (the "KNOWLEDGE GRAPH DIRECTIVES") that tells the agent:

1. **Ask the Vault First:** Use `ask_vault(query)` to get precise, scored paragraph excerpts
   that directly answer the user's question — the fastest and most token-efficient first step.
2. **Broad Search if Needed:** Use `search_memories(query)` to find full memories by title
   and content when chunk results are insufficient.
3. **Deep Dive:** Use `get_memory_context(file_id)` to get the memory body plus its
   immediate graph neighbours in a single call.
4. **Execution:** Apply the skill loaded from the vault.

**Reflection Mode** is also detected automatically: if the user prompt contains keywords like
"summarize", "weekly review", or "reflect on", the hook emits a FLOW D directive instead,
instructing the agent to call `gather_sources(topic, 5)` and `create_memory(...)`.

### The Post-Invoke Hook (`post-invoke.sh`)
When the agent finishes (or as a final reflection step), the post-invoke hook reminds the
agent about the **Memory Consolidation** phase. The agent is expected to use `create_memory`
or `update_memory` to document new learnings, growing the vault's intelligence automatically.

On task failure, the hook emits a failure-documentation directive instructing the agent to
save a failure-log memory tagged for future discoverability.

---

*By treating Markdown files as executable Agent Skills, MemoLink turns any standard repository
into a self-organizing, self-improving AI brain.*
