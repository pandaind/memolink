package ist.logic.viewer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MemoLink viewer starter.
 *
 * <pre>
 * memolink:
 *   notes-dir: /path/to/your/notes   # directory containing .md files
 * </pre>
 */
@ConfigurationProperties("memolink")
public class MemoLinkViewerProperties {

    /** Root directory containing the Markdown files to index. */
    private String notesDir = System.getProperty("user.home") + "/notes";

    public String getNotesDir() {
        return notesDir;
    }

    public void setNotesDir(String notesDir) {
        this.notesDir = notesDir;
    }
}
