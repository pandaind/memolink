package ist.logic.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MemoLink Spring AI starter.
 *
 * <pre>
 * memolink:
 *   vault-dir: /path/to/your/vault   # directory containing .md files
 * </pre>
 */
@ConfigurationProperties("memolink")
public class MemoLinkAiProperties {

    /** Root directory containing the Markdown files to index. */
    private String vaultDir = System.getProperty("user.home") + "/vault";

    public String getVaultDir() {
        return vaultDir;
    }

    public void setVaultDir(String vaultDir) {
        this.vaultDir = vaultDir;
    }

    public static class Lucene {
        private String storage = "memory";
        public String getStorage() { return storage; }
        public void setStorage(String storage) { this.storage = storage; }
    }
    private Lucene lucene = new Lucene();
    public Lucene getLucene() { return lucene; }
    public void setLucene(Lucene lucene) { this.lucene = lucene; }
}
