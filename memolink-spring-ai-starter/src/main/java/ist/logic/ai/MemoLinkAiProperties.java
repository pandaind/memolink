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
}
