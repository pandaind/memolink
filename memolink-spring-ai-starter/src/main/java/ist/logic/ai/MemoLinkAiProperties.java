package ist.logic.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MemoLink Spring AI starter.
 *
 * <pre>
 * memolink:
 *   vault-dir: /path/to/your/vault   # directory containing .md files
 *   reranker:
 *     enabled: true                  # enable cross-encoder reranking
 * </pre>
 */
@ConfigurationProperties("memolink")
public class MemoLinkAiProperties {

    /** Root directory containing the Markdown files to index. */
    private String vaultDir = System.getProperty("user.home") + "/vault";

    public String getVaultDir() { return vaultDir; }
    public void setVaultDir(String vaultDir) { this.vaultDir = vaultDir; }

    public static class Lucene {
        private String storage = "memory";
        public String getStorage() { return storage; }
        public void setStorage(String storage) { this.storage = storage; }
    }
    private Lucene lucene = new Lucene();
    public Lucene getLucene() { return lucene; }
    public void setLucene(Lucene lucene) { this.lucene = lucene; }

    /**
     * Controls the optional cross-encoder reranking stage.
     * Mirrors {@code ist.logic.mcp.config.RerankProperties}.
     */
    public static class Reranker {
        /** Whether cross-encoder reranking is enabled. Default: false. */
        private boolean enabled          = false;
        /** Retrieve N * candidateMultiple candidates before reranking. Default: 4. */
        private int     candidateMultiple = 4;
        /** Hard cap on candidates sent to the cross-encoder. Default: 20. */
        private int     maxCandidates    = 20;

        public boolean isEnabled()            { return enabled; }
        public int     getCandidateMultiple() { return candidateMultiple; }
        public int     getMaxCandidates()     { return maxCandidates; }

        public void setEnabled(boolean enabled)                 { this.enabled = enabled; }
        public void setCandidateMultiple(int candidateMultiple) { this.candidateMultiple = candidateMultiple; }
        public void setMaxCandidates(int maxCandidates)         { this.maxCandidates = maxCandidates; }
    }
    private Reranker reranker = new Reranker();
    public Reranker getReranker() { return reranker; }
    public void setReranker(Reranker reranker) { this.reranker = reranker; }
}
