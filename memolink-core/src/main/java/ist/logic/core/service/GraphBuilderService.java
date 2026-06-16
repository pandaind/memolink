package ist.logic.core.service;

import ist.logic.core.model.GraphEdge;
import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.model.MdFileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Orchestrates the full pipeline:
 *   scan → parse → score relationships → build KnowledgeGraph
 *
 * <p>Use {@link #build(Path)} for the initial load and
 * {@link #buildIncremental(KnowledgeGraph, Set)} for subsequent updates.
 * Incremental mode re-parses only the changed files, avoiding a full vault scan.
 */
public class GraphBuilderService {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilderService.class);

    private final MdFileScannerService scanner = new MdFileScannerService();
    private final MdFileParserService  parser  = new MdFileParserService();
    private final RelationshipEngine   engine  = new RelationshipEngine();
    private final ObjectMapper         mapper  = new ObjectMapper();

    public static class CacheEntry {
        public long lastModified;
        public float[] embedding;
        public List<float[]> chunkEmbeddings;
        public CacheEntry() {}
        public CacheEntry(long lastModified, float[] embedding, List<float[]> chunkEmbeddings) {
            this.lastModified = lastModified;
            this.embedding = embedding;
            this.chunkEmbeddings = chunkEmbeddings;
        }
    }

    /** Set during {@link #build}; used by incremental rebuilds to compute relative-path IDs. */
    private volatile Path rootDir;
    private Path cacheFile = null;
    private Path timestampsFile = null;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> timestamps = new ConcurrentHashMap<>();
    private boolean useDisk = false;

    public void setUseDisk(boolean useDisk) {
        this.useDisk = useDisk;
    }

    public KnowledgeGraph build(Path rootDir) throws IOException {
        return build(rootDir, null);
    }

    public KnowledgeGraph build(Path rootDir, EmbeddingService embeddingService) throws IOException {
        this.rootDir = rootDir;   // remember for incremental rebuilds
        log.info("Scanning md files in: {}", rootDir.toAbsolutePath());
        List<Path> paths = scanner.scan(rootDir);
        log.info("Found {} md files", paths.size());

        List<MdFileMetadata> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            try {
                MdFileMetadata m = parser.parse(path, rootDir);
                long lastMod = Files.getLastModifiedTime(path).toMillis();
                Long cachedMod = timestamps.get(m.getId());
                if (cachedMod != null && cachedMod >= lastMod) {
                    m.setModified(false);
                } else {
                    m.setModified(true);
                }
                files.add(m);
            } catch (IOException e) {
                log.warn("Skipping unreadable file {}: {}", path, e.getMessage());
            }
        }

        embedAll(files, embeddingService);

        List<GraphEdge> edges = engine.buildEdges(files);
        log.info("Built graph: {} nodes, {} edges", files.size(), edges.size());

        return new KnowledgeGraph(files, edges);
    }

    public KnowledgeGraph buildIncremental(KnowledgeGraph current, Set<Path> changedPaths) throws IOException {
        return buildIncremental(current, changedPaths, null);
    }

    public KnowledgeGraph buildIncremental(KnowledgeGraph current, Set<Path> changedPaths,
                                           EmbeddingService embeddingService) throws IOException {
        // Seed from current graph, keyed by file ID
        Map<String, MdFileMetadata> fileMap = new LinkedHashMap<>();
        for (MdFileMetadata m : current.getAllMdFiles()) {
            fileMap.put(m.getId(), m);
        }

        for (Path path : changedPaths) {
            // Use relative path as ID, falling back to filename if rootDir not yet set
            String id = rootDir != null
                    ? rootDir.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                    : path.getFileName().toString();
            if (Files.exists(path)) {
                try {
                    MdFileMetadata updated = rootDir != null
                            ? parser.parse(path, rootDir)
                            : parser.parse(path);
                    updated.setModified(true);
                    fileMap.put(updated.getId(), updated);
                } catch (IOException e) {
                    log.warn("Skipping unreadable file {}: {}", path, e.getMessage());
                }
            } else {
                fileMap.remove(id);
            }
        }

        List<MdFileMetadata> files = new ArrayList<>(fileMap.values());
        // Only embed newly-changed files (carry existing embeddings forward)
        List<MdFileMetadata> needsEmbed = changedPaths.stream()
                .map(p -> rootDir != null
                        ? rootDir.relativize(p).toString().replace(java.io.File.separatorChar, '/')
                        : p.getFileName().toString())
                .map(fileMap::get)
                .filter(m -> m != null && !m.hasEmbedding())
                .toList();
        embedAll(needsEmbed, embeddingService);

        List<GraphEdge> edges = engine.buildEdges(files);
        log.info("Incremental rebuild: {} nodes, {} edges ({} file(s) changed)",
                 files.size(), edges.size(), changedPaths.size());

        return new KnowledgeGraph(files, edges);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void initCache() {
        if (rootDir != null && cacheFile == null) {
            Path memolinkDir = rootDir.resolve(".memolink");
            try {
                if (!Files.exists(memolinkDir)) Files.createDirectories(memolinkDir);
                cacheFile = memolinkDir.resolve("embeddings.json");
                timestampsFile = memolinkDir.resolve("timestamps.json");
            } catch (IOException e) {
                log.warn("Could not create .memolink directory for caching: {}", e.getMessage());
            }

            if (timestampsFile != null && Files.exists(timestampsFile)) {
                try {
                    Map<String, Long> loaded = mapper.readValue(timestampsFile.toFile(),
                            new TypeReference<Map<String, Long>>() {});
                    if (loaded != null) timestamps.putAll(loaded);
                } catch (IOException e) {
                    log.warn("Failed to read timestamps cache: {}", e.getMessage());
                }
            }

            if (!useDisk && cacheFile != null && Files.exists(cacheFile)) {
                try {
                    Map<String, CacheEntry> loaded = mapper.readValue(cacheFile.toFile(), 
                            new TypeReference<Map<String, CacheEntry>>() {});
                    if (loaded != null) cache.putAll(loaded);
                    log.info("Loaded {} cached embeddings", cache.size());
                } catch (IOException e) {
                    log.warn("Failed to read embeddings cache, will rebuild: {}", e.getMessage());
                }
            }
        }
    }

    private void embedAll(List<MdFileMetadata> files, EmbeddingService embeddingService) {
        if (embeddingService == null || !embeddingService.isAvailable()) return;
        
        initCache();

        for (MdFileMetadata m : files) {
            boolean needsEmbedding = !m.hasEmbedding() || m.getChunkEmbeddings() == null;
            if (needsEmbedding) {
                String id = m.getId();
                long lastMod = 0;
                try {
                    lastMod = Files.getLastModifiedTime(m.getFilePath()).toMillis();
                } catch (IOException ignored) {}

                CacheEntry entry = cache.get(id);
                if (entry != null && entry.lastModified >= lastMod && entry.embedding != null && entry.chunkEmbeddings != null) {
                    m.setEmbedding(entry.embedding);
                    m.setChunkEmbeddings(entry.chunkEmbeddings);
                    // We must still reconstruct chunkTexts since we don't cache texts
                    List<String> texts = new ArrayList<>();
                    if (m.getContent() != null) {
                        for (String p : m.getContent().split("\\n\\s*\\n")) {
                            String clean = p.trim();
                            if (clean.length() >= 50) texts.add(clean);
                        }
                    }
                    m.setChunkTexts(texts);
                }
            }
        }
    }

    public void computeMissingEmbeddings(KnowledgeGraph graph, EmbeddingService embeddingService) {
        int count = 0;

        for (MdFileMetadata m : graph.getAllMdFiles()) {
            if (useDisk && !m.isModified()) {
                continue; // Safe on disk, skip embedding calculation to save API calls
            }
            boolean needsEmbedding = !m.hasEmbedding() || m.getChunkEmbeddings() == null;
            if (needsEmbedding) {
                long lastMod = 0;
                try {
                    lastMod = Files.getLastModifiedTime(m.getFilePath()).toMillis();
                } catch (IOException ignored) {}

                float[] emb = embeddingService.embedNote(m.getTitle(), m.getContent());
                
                List<float[]> cEmbs = new ArrayList<>();
                List<String> cTexts = new ArrayList<>();
                if (m.getContent() != null) {
                    for (String p : m.getContent().split("\\n\\s*\\n")) {
                        String clean = p.trim();
                        if (clean.length() >= 50) {
                            float[] pVec = embeddingService.embed(clean);
                            if (pVec != null) {
                                cEmbs.add(pVec);
                                cTexts.add(clean);
                            }
                        }
                    }
                }

                if (emb != null) { 
                    m.setEmbedding(emb); 
                    m.setChunkEmbeddings(cEmbs);
                    m.setChunkTexts(cTexts);
                    if (!useDisk) {
                        cache.put(m.getId(), new CacheEntry(lastMod, emb, cEmbs));
                    }
                    timestamps.put(m.getId(), lastMod);
                    count++; 
                    try {
                        Thread.sleep(100); // Throttle to prevent CPU overheating
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        
        if (count > 0) {
            log.info("Computed {} new embeddings sequentially in background", count);
            if (!useDisk && cacheFile != null) {
                try {
                    mapper.writeValue(cacheFile.toFile(), cache);
                    log.info("Saved embeddings cache to disk");
                } catch (IOException e) {
                    log.warn("Failed to write embeddings cache: {}", e.getMessage());
                }
            }
            if (timestampsFile != null) {
                try {
                    mapper.writeValue(timestampsFile.toFile(), timestamps);
                    log.info("Saved timestamps cache to disk");
                } catch (IOException e) {
                    log.warn("Failed to write timestamps cache: {}", e.getMessage());
                }
            }
        }
    }
}
