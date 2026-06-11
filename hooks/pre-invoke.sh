#!/bin/bash
# ==============================================================================
# MemoLink Pre-Invoke Hook
# Outputs enriched system instructions to force the agent to use MCP tools.
# ==============================================================================

# Extract the user prompt (frameworks usually pass this via $1 or env vars)
USER_PROMPT="${1:-$COPILOT_PROMPT}"

# Capture environmental context if available (useful for the agent's context)
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
CURRENT_DIR=$(pwd)

cat <<EOF
--- MEMOLINK KNOWLEDGE GRAPH DIRECTIVES ---

You are connected to the MemoLink Knowledge Vault via the Model Context Protocol (MCP).
You are operating in the repository directory: $CURRENT_DIR
Current Git Branch: $CURRENT_BRANCH

CRITICAL WORKFLOW INSTRUCTIONS:
1. [DYNAMIC SKILLS DISCOVERY] BEFORE writing any code or answering the user, you MUST use the 'search_md_files' MCP tool to dynamically discover relevant skills, instructions, or past architectural decisions related to: "$USER_PROMPT". Treat the returned markdown files as executable skills.
2. [EVALUATION] Review the 'score' of the returned search results. Ignore results with a score below 0.3.
3. [DEEP DIVE] Use 'get_graph_context' on the most relevant file ID. This provides both the skill content AND its immediate neighbors in the knowledge graph, giving you maximum context with minimal tool calls.
4. [EXECUTION] Only after reading the dynamically loaded skills should you fulfill the user's request.
5. [PERSISTENCE & SKILL UPDATE] When the task is complete, you MUST use the 'create_md_file' or 'update_md_file' tools to document what you learned. If you invented a new workflow or pattern, save it as a new skill. If you improved an existing skill, update it. Tag new skills with #${CURRENT_BRANCH}.

Do not skip these steps. The vault contains critical project-specific rules that supersede your base training.
EOF
