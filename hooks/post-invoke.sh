#!/bin/bash
# ==============================================================================
# MemoLink Post-Invoke Hook
# Cleanup and telemetry execution.
# ==============================================================================

# Extract the agent summary and exit code from the framework
AGENT_SUMMARY="${1:-No summary provided}"
EXIT_CODE="${2:-0}"

# Log metadata
END_TIME=$(date +'%Y-%m-%d %H:%M:%S')

echo "--- MEMOLINK TELEMETRY ---"

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "✅ [SUCCESS] Agent execution completed at $END_TIME."
    echo "ℹ️ Note: The agent should have already logged its architectural decisions to the MemoLink Vault via the 'create_md_file' MCP tool."
else
    echo "❌ [FAILURE] Agent execution failed with exit code: $EXIT_CODE at $END_TIME."
    echo "⚠️ Recommendation: If the failure was due to an environment issue, instruct the agent to document the fix in MemoLink for future reference."
fi

exit 0
