#!/bin/bash
# ==============================================================================
# MemoLink Pre-Invoke Hook
# Outputs enriched system instructions to force the agent to use MCP tools.
# ==============================================================================

# Extract the user prompt (frameworks usually pass this via $1 or env vars)
USER_PROMPT="${1:-$COPILOT_PROMPT}"

# Score threshold — lower = more permissive, higher = stricter relevance filter.
# Override by setting MEMOLINK_SCORE_THRESHOLD in your environment or .env file.
SCORE_THRESHOLD="${MEMOLINK_SCORE_THRESHOLD:-0.3}"

# MemoLink server base URL
MEMOLINK_URL="${MEMOLINK_URL:-http://localhost:8765}"

CURRENT_DIR=$(pwd)

# ==============================================================================
# ISSUE 5: Health check — abort if MemoLink is unreachable
# ==============================================================================
if ! curl -sf "${MEMOLINK_URL}/actuator/health" -o /dev/null 2>/dev/null; then
    echo "⚠️  MemoLink server is offline or unreachable at ${MEMOLINK_URL}."
    echo "    Start it with: docker compose --profile compression up -d"
    echo "    Skipping KNOWLEDGE GRAPH DIRECTIVES — agent will operate without vault context."
    exit 0
fi

# ==============================================================================
# ISSUE 4: Detect reflection / summarization prompts → emit Flow D directive
# ==============================================================================
PROMPT_LOWER=$(echo "$USER_PROMPT" | tr '[:upper:]' '[:lower:]')

is_reflection=false
for keyword in "summarize" "summarise" "synthesize" "synthesise" "weekly review" \
               "generate report" "write a summary" "overview of" "compile a" \
               "reflect on" "reflection" "roundup" "digest"; do
    if echo "$PROMPT_LOWER" | grep -qF "$keyword"; then
        is_reflection=true
        break
    fi
done

# ==============================================================================
# Emit the appropriate directive block
# ==============================================================================

if [ "$is_reflection" = true ]; then

cat <<EOF
--- MEMOLINK KNOWLEDGE GRAPH DIRECTIVES (REFLECTION MODE) ---

You are connected to the MemoLink Knowledge Vault via the Model Context Protocol (MCP).
You are operating in the repository directory: $CURRENT_DIR
Score threshold for relevance: $SCORE_THRESHOLD

The user's request has been identified as a REFLECTION / SYNTHESIS task.
Follow FLOW D exactly:

CRITICAL WORKFLOW INSTRUCTIONS:
1. [ORIENTATION]  Call 'get_memory_summary()' first. Review the top hub notes and
   dominant tags to identify the best search terms for the topic.
2. [GATHER]       Call 'gather_reflection_sources(topic, 5)' with the core topic
   extracted from the user prompt: "$USER_PROMPT".
   This returns compressed excerpts from the top 5 related notes in a single call.
3. [EVALUATE]     Discard any source whose relevance score is below $SCORE_THRESHOLD.
4. [SYNTHESIZE]   Read the excerpts and generate a comprehensive summary or report.
5. [SAVE]         Use 'create_md_file(...)' to save the synthesis.
   - Tag it with 'reflection'.
   - Add the source file_ids to the 'wiki_links' array so the Knowledge Graph
     links the reflection back to its sources.

Do not skip these steps. The vault contains critical project-specific context
that supersedes your base training.
EOF

else

cat <<EOF
--- MEMOLINK KNOWLEDGE GRAPH DIRECTIVES ---

You are connected to the MemoLink Knowledge Vault via the Model Context Protocol (MCP).
You are operating in the repository directory: $CURRENT_DIR
Score threshold for relevance: $SCORE_THRESHOLD

CRITICAL WORKFLOW INSTRUCTIONS:
1. [ORIENTATION]        If the user prompt is broad or ambiguous, call
   'get_memory_summary()' FIRST. It is a zero-cost call (no embeddings, instant)
   that returns graph statistics, hub notes, and top tags — helping you choose
   a better search query before spending a tool call on 'search_md_files'.

2. [DYNAMIC SKILLS DISCOVERY]  Use the 'search_md_files' MCP tool to dynamically
   discover relevant skills, instructions, or past architectural decisions related
   to: "$USER_PROMPT". Treat the returned markdown files as executable skills.

3. [EVALUATION]  Review the 'score' of the returned search results.
   Ignore results with a score below $SCORE_THRESHOLD.

4. [DEEP DIVE]   Use 'get_graph_context' on the most relevant file ID.
   This provides both the skill content AND its immediate neighbours in the
   knowledge graph, giving you maximum context with minimal tool calls.

5. [EXECUTION]   Only after reading the dynamically loaded skills should you
   fulfil the user's request.

6. [SKILL UPDATE]  When the task is complete, use 'create_md_file' or
   'update_md_file' to document what you learned. If you invented a new workflow
   or pattern, save it as a new skill. If you improved an existing skill, update
   it with the new information.

Do not skip these steps. The vault contains critical project-specific rules
that supersede your base training.
EOF

fi
