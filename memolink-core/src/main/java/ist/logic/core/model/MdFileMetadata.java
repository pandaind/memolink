package ist.logic.core.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Parsed metadata for a single markdown note.
 * Immutable once constructed.
 */
public class MdFileMetadata {

    private final String id;          // filename, e.g. "spring.md"
    private final String title;       // first H1 heading or filename stem
    private final String content;     // raw markdown content
    private final Path filePath;      // absolute path on disk
    private final Set<String> wikiLinks;
    private final Set<String> tags;
    private final Set<String> keywords;
    private final Set<String> headings;

    public MdFileMetadata(String id, String title, String content, Path filePath,
                        Set<String> wikiLinks, Set<String> tags,
                        Set<String> keywords, Set<String> headings) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.filePath = filePath;
        this.wikiLinks  = Collections.unmodifiableSet(new HashSet<>(wikiLinks));
        this.tags       = Collections.unmodifiableSet(new HashSet<>(tags));
        this.keywords   = Collections.unmodifiableSet(new HashSet<>(keywords));
        this.headings   = Collections.unmodifiableSet(new HashSet<>(headings));
    }

    public String getId()           { return id; }
    public String getTitle()        { return title; }
    public String getContent()      { return content; }
    public Path   getFilePath()     { return filePath; }
    public Set<String> getWikiLinks() { return wikiLinks; }
    public Set<String> getTags()      { return tags; }
    public Set<String> getKeywords()  { return keywords; }
    public Set<String> getHeadings()  { return headings; }

    @Override
    public String toString() {
        return "MdFileMetadata{id='" + id + "', tags=" + tags.size()
                + ", wikiLinks=" + wikiLinks.size() + "}";
    }
}
