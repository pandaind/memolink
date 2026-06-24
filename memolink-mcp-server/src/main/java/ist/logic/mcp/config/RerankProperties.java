package ist.logic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties controlling the optional cross-encoder reranking stage.
 *
 * <pre>
 * memolink:
 *   reranker:
 *     enabled: false            # set to true to enable cross-encoder reranking
 *     candidate-multiple: 4    # retrieve N * this many candidates before reranking
 *     max-candidates: 20       # hard cap on candidates sent to the cross-encoder
 * </pre>
 */
@ConfigurationProperties("memolink.reranker")
public class RerankProperties {

    /** Whether cross-encoder reranking is enabled. Default: false. */
    private boolean enabled = false;

    /**
     * Multiplier applied to the requested result count to determine how many
     * Stage-1 candidates to retrieve before reranking.
     * Higher values give the reranker more material to work from, at the cost
     * of more cross-encoder inferences. Default: 4.
     */
    private int candidateMultiple = 4;

    /**
     * Hard cap on the number of candidates sent to the cross-encoder in a
     * single request. Prevents runaway latency on very large vaults. Default: 20.
     */
    private int maxCandidates = 20;

    public boolean isEnabled()           { return enabled; }
    public int     getCandidateMultiple() { return candidateMultiple; }
    public int     getMaxCandidates()     { return maxCandidates; }

    public void setEnabled(boolean enabled)                   { this.enabled = enabled; }
    public void setCandidateMultiple(int candidateMultiple)   { this.candidateMultiple = candidateMultiple; }
    public void setMaxCandidates(int maxCandidates)           { this.maxCandidates = maxCandidates; }
}
