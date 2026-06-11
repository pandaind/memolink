# MemoLink Agentic Integration & Dynamic Skills

This document explains the **How**, **What**, and **Why** of integrating MemoLink into advanced agentic frameworks (like GitHub Copilot, Claude Desktop, or custom IDE agents) using Pre and Post invocation hooks.

---

## 1. WHAT is this Architecture?

Instead of treating MemoLink as just a Markdown viewer, we treat the MemoLink Vault as a **Dynamic Skill Library and Persistent Agent Memory**. 

By using framework execution hooks (e.g., `.github/hooks/pre-invoke.sh`), we intercept the agent *before* it begins a task and inject strict, structured instructions into its system prompt. These instructions force the agent to use the MemoLink MCP Server to retrieve relevant "Skills" (markdown files) dynamically, and write new skills back to the vault when it finishes.

## 2. WHY use Dynamic Skills via MemoLink?

1. **Token Efficiency:** Loading dozens of static `skills/*.md` files into the LLM's context window for every request is expensive, slow, and causes "lost in the middle" hallucinations.
2. **Contextual Accuracy:** MemoLink's semantic search (via local ONNX embeddings) ensures the agent only retrieves the 1 or 2 skills specifically relevant to the current user prompt.
3. **Graph-Powered Deep Dives:** Because skills are stored in a Knowledge Graph, if `react-skill.md` links to `api-auth-skill.md`, the agent can traverse the graph and read both, understanding system dependencies automatically.
4. **Self-Improving Agents:** Agents can use the MCP tools to write new markdown files. When an agent solves a tough bug, it saves the solution as a new "Skill" in the vault. The next time it encounters the bug, it dynamically discovers the skill it wrote last week.

## 3. HOW does it work?

### The Pre-Invoke Hook (`pre-invoke.sh`)
When the user asks the agent to perform a task, the framework triggers the pre-invoke hook. 
The hook outputs a strict prompt (the "KNOWLEDGE GRAPH DIRECTIVES") that tells the agent:
1. **Dynamic Skills Discovery**: "Use the `search_md_files` tool using the user's prompt to find relevant skills."
2. **Deep Dive**: "Use `get_graph_context` to understand how the skill connects to the rest of the project."
3. **Execution**: "Apply the skill."

### The Post-Invoke Hook (`post-invoke.sh`)
When the agent finishes (or as a final reflection step), the post-invoke hook reminds the agent or the framework about the **Skill Update** phase.
The agent is expected to use the `create_md_file` or `update_md_file` MCP tools to document new learnings, effectively growing the MemoLink vault's intelligence automatically over time.

---
*By treating Markdown files as executable Agent Skills, MemoLink turns any standard repository into a self-organizing, self-improving AI brain.*
