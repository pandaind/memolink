package ist.logic.core.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parsed metadata for a single markdown note.
 * Core fields are immutable once constructed; access-tracking fields
 * ({@code accessCount}, {@code lastAccessedMs}) are updated concurrently
 * as the note is read by tools.
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

    // ── Capability 1: semantic embedding (384-dim, null until computed) ─────
    private volatile float[] embedding;
    private volatile java.util.List<String> chunkTexts;
    private volatile java.util.List<float[]> chunkEmbeddings;

    // ── Capability 5: metadata ranking ──────────────────────────────────────
    /** 0–10 user-assigned importance (default 0 = unset). */
    private volatile int importance = 0;
    private final AtomicInteger accessCount   = new AtomicInteger(0);
    private final AtomicLong    lastAccessedMs = new AtomicLong(0);

    // ── Transient State ─────────────────────────────────────────────────────
    /** Flags whether this file changed on disk compared to the index. */
    private transient volatile boolean modified = true;

    public MdFileMetadata(String id, String title, String content, Path filePath,
                        Set<String> wikiLinks, Set<String> tags,
                        Set<String> keywords, Set<String> headings) {
        this(id, title, content, filePath, wikiLinks, Map.of(), tags, keywords, headings);
    }

    public MdFileMetadata(String id, String title, String content, Path filePath,
                        Set<String> wikiLinks, Map<String, String> wikiLinkTypes,
                        Set<String> tags, Set<String> keywords, Set<String> headings) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.filePath = filePath;
        this.wikiLinks     = Collections.unmodifiableSet(new HashSet<>(wikiLinks));
        this.wikiLinkTypes = Collections.unmodifiableMap(new HashMap<>(wikiLinkTypes));
        this.tags          = Collections.unmodifiableSet(new HashSet<>(tags));
        this.keywords      = Collections.unmodifiableSet(new HashSet<>(keywords));
        this.headings      = Collections.unmodifiableSet(new HashSet<>(headings));
    }

    public String getId()             { return id; }
    public String getTitle()          { return title; }
    public String getContent()        { return content; }
    public Path   getFilePath()       { return filePath; }
    public Set<String> getWikiLinks() { return wikiLinks; }
    /** Returns the explicit relationship type for a wiki-link target, or {@code null} if none was specified. */
    public String getWikiLinkType(String targetId) { return wikiLinkTypes.get(targetId); }
    public Map<String, String> getWikiLinkTypes()  { return wikiLinkTypes; }
    public Set<String> getTags()      { return tags; }
    public Set<String> getKeywords()  { return keywords; }
    public Set<String> getHeadings()  { return headings; }

    // ── Embedding ────────────────────────────────────────────────────────────
    public float[] getEmbedding()              { return embedding; }
    public void    setEmbedding(float[] emb)   { this.embedding = emb; }
    public boolean hasEmbedding()              { return embedding != null; }

    public java.util.List<String> getChunkTexts() { return chunkTexts; }
    public void setChunkTexts(java.util.List<String> chunkTexts) { this.chunkTexts = chunkTexts; }
    
    public java.util.List<float[]> getChunkEmbeddings() { return chunkEmbeddings; }
    public void setChunkEmbeddings(java.util.List<float[]> chunkEmbeddings) { this.chunkEmbeddings = chunkEmbeddings; }

    // ── Metadata ranking ─────────────────────────────────────────────────────
    public int  getImportance()              { return importance; }
    public void setImportance(int v)         { this.importance = Math.max(0, Math.min(10, v)); }

    public int  getAccessCount()             { return accessCount.get(); }
    public long getLastAccessedMs()          { return lastAccessedMs.get(); }

    /** Call this whenever the note is retrieved by a tool. */
    public void recordAccess() {
        accessCount.incrementAndGet();
        lastAccessedMs.set(System.currentTimeMillis());
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    @Override
    public String toString() {
        return "MdFileMetadata{id='" + id + "', tags=" + tags.size()
                + ", wikiLinks=" + wikiLinks.size() + "}";
    }
}
