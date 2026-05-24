package ist.logic.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MemoLink Spring AI starter.
 *
 * <pre>
 * memolink:
 *   notes-dir: /path/to/your/notes   # directory containing .md files
 * </pre>
 */
@ConfigurationProperties("memolink")
public class MemoLinkAiProperties {

    /** Root directory containing the Markdown notes to index. */
    private String notesDir = System.getProperty("user.home") + "/notes";

    public String getNotesDir() {
        return notesDir;
    }

    public void setNotesDir(String notesDir) {
        this.notesDir = notesDir;
    }
}
