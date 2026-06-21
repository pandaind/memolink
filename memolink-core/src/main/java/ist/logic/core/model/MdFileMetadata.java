package ist.logic.core.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parsed metadata for a single markdown memory (note).
 *
 * <p>Core fields are immutable once constructed. Access-tracking fields
 * ({@code accessCount}, {@code lastAccessedMs}) are updated concurrently
 * as the memory is read by tools.
 */
public class MdFileMetadata {

    private final String id;
    private final String title;
    private final String content;
    private final Path filePath;
    private final Set<String> wikiLinks;
    private final Map<String, String> wikiLinkTypes; // target id → relationship type
    private final Set<String> tags;
    private final Set<String> keywords;
    private final Set<String> headings;

    // ── Semantic embedding (384-dim, null until computed) ────────────────────
    private volatile float[] embedding;
    private volatile List<String> chunkTexts;
    private volatile List<float[]> chunkEmbeddings;

    // ── Metadata ranking ─────────────────────────────────────────────────────
    /** 0–10 user-assigned importance (default 0 = unset). */
    private volatile int importance = 0;
    private final AtomicInteger accessCount    = new AtomicInteger(0);
    private final AtomicLong    lastAccessedMs = new AtomicLong(0);

    // ── Incremental index tracking ───────────────────────────────────────────
    /** True when this file has changed on disk relative to the current Lucene index. */
    private transient volatile boolean modified = true;

    public MdFileMetadata(String id, String title, String content, Path filePath,
                          Set<String> wikiLinks, Set<String> tags,
                          Set<String> keywords, Set<String> headings) {
        this(id, title, content, filePath, wikiLinks, Map.of(), tags, keywords, headings);
    }

    public MdFileMetadata(String id, String title, String content, Path filePath,
                          Set<String> wikiLinks, Map<String, String> wikiLinkTypes,
                          Set<String> tags, Set<String> keywords, Set<String> headings) {
        this.id            = id;
        this.title         = title;
        this.content       = content;
        this.filePath      = filePath;
        this.wikiLinks     = Collections.unmodifiableSet(new HashSet<>(wikiLinks));
        this.wikiLinkTypes = Collections.unmodifiableMap(new HashMap<>(wikiLinkTypes));
        this.tags          = Collections.unmodifiableSet(new HashSet<>(tags));
        this.keywords      = Collections.unmodifiableSet(new HashSet<>(keywords));
        this.headings      = Collections.unmodifiableSet(new HashSet<>(headings));
    }

    // ── Core fields ──────────────────────────────────────────────────────────

    public String             getId()            { return id; }
    public String             getTitle()         { return title; }
    public String             getContent()       { return content; }
    public Path               getFilePath()      { return filePath; }
    public Set<String>        getWikiLinks()     { return wikiLinks; }
    public Map<String, String> getWikiLinkTypes(){ return wikiLinkTypes; }
    public Set<String>        getTags()          { return tags; }
    public Set<String>        getKeywords()      { return keywords; }
    public Set<String>        getHeadings()      { return headings; }

    /** Returns the explicit relationship type for a wiki-link target, or {@code null} if none. */
    public String getWikiLinkType(String targetId) { return wikiLinkTypes.get(targetId); }

    // ── Semantic embedding ────────────────────────────────────────────────────

    public float[]       getEmbedding()                      { return embedding; }
    public void          setEmbedding(float[] emb)           { this.embedding = emb; }
    public boolean       hasEmbedding()                      { return embedding != null; }
    public List<String>  getChunkTexts()                     { return chunkTexts; }
    public void          setChunkTexts(List<String> texts)   { this.chunkTexts = texts; }
    public List<float[]> getChunkEmbeddings()                { return chunkEmbeddings; }
    public void          setChunkEmbeddings(List<float[]> e) { this.chunkEmbeddings = e; }

    // ── Metadata ranking ─────────────────────────────────────────────────────

    public int  getImportance()        { return importance; }
    public void setImportance(int v)   { this.importance = Math.max(0, Math.min(10, v)); }
    public int  getAccessCount()       { return accessCount.get(); }
    public long getLastAccessedMs()    { return lastAccessedMs.get(); }

    /** Call whenever this memory is retrieved by a tool to update access stats. */
    public void recordAccess() {
        accessCount.incrementAndGet();
        lastAccessedMs.set(System.currentTimeMillis());
    }

    // ── Incremental index tracking ────────────────────────────────────────────

    public boolean isModified()              { return modified; }
    public void    setModified(boolean flag) { this.modified = flag; }

    @Override
    public String toString() {
        return "MdFileMetadata{id='" + id + "', tags=" + tags.size()
                + ", wikiLinks=" + wikiLinks.size() + "}";
    }
}
