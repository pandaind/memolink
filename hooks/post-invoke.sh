#!/bin/bash
# ==============================================================================
# MemoLink Post-Invoke Hook
# Outputs a final directive forcing the agent to consolidate skills and memory
# before closing. Frameworks that inject this output as a final agent prompt
# (Copilot, Cursor, etc.) will trigger the agent to write back to the vault.
# ==============================================================================

AGENT_SUMMARY="${1:-No summary provided}"
EXIT_CODE="${2:-0}"

MEMOLINK_URL="${MEMOLINK_URL:-http://localhost:8765}"
SCORE_THRESHOLD="${MEMOLINK_SCORE_THRESHOLD:-0.3}"

# If MemoLink is offline, there is nothing useful to tell the agent.
if ! curl -sf "${MEMOLINK_URL}/actuator/health" -o /dev/null 2>/dev/null; then
    echo "⚠️  MemoLink server is offline — skipping post-task skill consolidation directive."
    exit 0
fi

# ==============================================================================
# Emit the post-task consolidation directive
# ==============================================================================

if [ "$EXIT_CODE" -eq 0 ]; then

cat <<EOF
--- MEMOLINK POST-TASK DIRECTIVE ---

The task has completed successfully. Before you close, you MUST perform a
final Skill Consolidation pass. This is non-optional.

CONSOLIDATION WORKFLOW:
1. [ORIENTATION]   Call 'get_memory_summary()' to get a live view of the vault.
   Check if any hub notes are now outdated based on what you just did.

2. [DECIDE]        For EACH meaningful thing you learned, invented, or fixed
   during this task, choose one of:
   a) NEW SKILL    → Call 'create_md_file(file_id, title, body, wiki_links, tags)'
                     to save it as a new reusable skill.
   b) UPDATE SKILL → Call 'get_md_file(file_id)' first, then call 'update_md_file(...)'
                     to append your new knowledge to an existing note.

3. [IMPORTANCE]    If the note you created or updated is foundational to the
   project (an architectural decision, a hard-won bug fix, a core pattern),
   call 'set_note_importance(file_id, score)' with a score of 7–10 so that
   future agents always surface it first.

4. [LINK]          Ensure all new notes include 'wiki_links' pointing to
   related existing notes. This keeps the Knowledge Graph dense and accurate.

Do not skip this step. A vault that is never written to becomes stale.
The skills you save now will be found by the next agent handling a similar task.

Task Summary provided by framework: "$AGENT_SUMMARY"
EOF

else

cat <<EOF
--- MEMOLINK POST-TASK DIRECTIVE (TASK FAILED) ---

The task exited with code $EXIT_CODE. Before you close, you MUST document
the failure so the next agent does not hit the same problem.

FAILURE DOCUMENTATION WORKFLOW:
1. [DIAGNOSE]     Summarize the root cause of the failure in 2-3 sentences.

2. [SAVE]         Call 'create_md_file(...)' to create a new note that captures:
   - What the task was trying to do.
   - What went wrong and why (the root cause).
   - The fix or workaround, if you discovered one.
   - What to try next time if you did NOT discover a fix.

3. [TAG]          Tag the note with 'failure-log' and any relevant technology
   tags (e.g. 'docker', 'spring-boot', 'mcp'). This makes it findable.

4. [IMPORTANCE]   Call 'set_note_importance(file_id, 8)' so this failure
   analysis is always surfaced early in future searches.

Do not skip this step. Undocumented failures are repeated failures.

Task Summary provided by framework: "$AGENT_SUMMARY"
Exit Code: $EXIT_CODE
EOF

fi

exit 0
