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
 * Orchestrates the full build pipeline:
 *   scan → parse → score relationships → build {@link KnowledgeGraph}.
 *
 * <p>Use {@link #build(Path, EmbeddingService)} for the initial load and
 * {@link #buildIncremental(KnowledgeGraph, Set, EmbeddingService)} for
 * subsequent hot-reload updates triggered by {@link GraphWatchService}.
 * Incremental mode re-parses only changed files, carrying existing embeddings forward.
 */
public class GraphBuilderService {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilderService.class);

    /** Minimum paragraph length (chars) to qualify as a chunk for semantic search. */
    private static final int MIN_CHUNK_LENGTH = 50;

    private final MdFileScannerService scanner = new MdFileScannerService();
    private final MdFileParserService  parser  = new MdFileParserService();
    private final RelationshipEngine   engine  = new RelationshipEngine();
    private final ObjectMapper         mapper  = new ObjectMapper();

    /**
     * Embedding cache entry. Stored in {@code .memolink/embeddings.json} (memory mode only).
     */
    public record CacheEntry(long lastModified, float[] embedding, List<float[]> chunkEmbeddings) {
        /** No-arg constructor required for Jackson deserialisation. */
        public CacheEntry() { this(0, null, null); }
    }

    /** Root directory of the vault — set on first {@link #build} call. */
    private volatile Path rootDir;

    private Path cacheFile      = null;
    private Path timestampsFile = null;
    private boolean cacheInitialised = false;

    /** In-memory embedding cache (memory mode only). */
    private final Map<String, CacheEntry> cache      = new ConcurrentHashMap<>();
    /** Last-modified timestamp per file ID (both modes). */
    private final Map<String, Long>       timestamps = new ConcurrentHashMap<>();

    private boolean useDisk = false;

    public void setUseDisk(boolean useDisk) {
        this.useDisk = useDisk;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public KnowledgeGraph build(Path rootDir) throws IOException {
        return build(rootDir, null);
    }

    public KnowledgeGraph build(Path rootDir, EmbeddingService embeddingService) throws IOException {
        this.rootDir = rootDir;
        initCache();

        log.info("Scanning md files in: {}", rootDir.toAbsolutePath());
        List<Path> paths = scanner.scan(rootDir);
        log.info("Found {} md files", paths.size());

        List<MdFileMetadata> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            try {
                MdFileMetadata m = parser.parse(path, rootDir);
                long lastMod    = Files.getLastModifiedTime(path).toMillis();
                Long cachedMod  = timestamps.get(m.getId());
                m.setModified(cachedMod == null || cachedMod < lastMod);
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

    public KnowledgeGraph buildIncremental(KnowledgeGraph current, Set<Path> changedPaths)
            throws IOException {
        return buildIncremental(current, changedPaths, null);
    }

    public KnowledgeGraph buildIncremental(KnowledgeGraph current,
                                           Set<Path> changedPaths,
                                           EmbeddingService embeddingService) throws IOException {
        // Seed from current graph, keyed by file ID
        Map<String, MdFileMetadata> fileMap = new LinkedHashMap<>();
        for (MdFileMetadata m : current.getAllMdFiles()) {
            fileMap.put(m.getId(), m);
        }

        for (Path path : changedPaths) {
            String id = toFileId(path);
            if (Files.exists(path)) {
                try {
                    MdFileMetadata updated = rootDir != null
                            ? parser.parse(path, rootDir) : parser.parse(path);
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

        // Only embed newly-changed files — carry existing embeddings forward
        List<MdFileMetadata> needsEmbed = changedPaths.stream()
                .map(this::toFileId)
                .map(fileMap::get)
                .filter(m -> m != null && !m.hasEmbedding())
                .toList();
        embedAll(needsEmbed, embeddingService);

        List<GraphEdge> edges = engine.buildEdges(files);
        log.info("Incremental rebuild: {} nodes, {} edges ({} file(s) changed)",
                files.size(), edges.size(), changedPaths.size());
        return new KnowledgeGraph(files, edges);
    }

    /**
     * Computes and caches embeddings for all memories that are missing them.
     * Called asynchronously in a background virtual thread after startup.
     */
    public void computeMissingEmbeddings(KnowledgeGraph graph, EmbeddingService embeddingService) {
        int count = 0;

        for (MdFileMetadata m : graph.getAllMdFiles()) {
            if (useDisk && !m.isModified()) {
                continue; // Already indexed on disk; skip to save CPU
            }
            if (m.hasEmbedding() && m.getChunkEmbeddings() != null) {
                continue;
            }

            long lastMod = readLastModified(m.getFilePath());
            float[] emb  = embeddingService.embedNote(m.getTitle(), m.getContent());

            List<float[]> cEmbs = new ArrayList<>();
            List<String>  cTexts = new ArrayList<>();
            for (String paragraph : splitParagraphs(m.getContent())) {
                float[] vec = embeddingService.embed(paragraph);
                if (vec != null) {
                    cEmbs.add(vec);
                    cTexts.add(paragraph);
                }
            }

            if (emb != null) {
                m.setEmbedding(emb);
                m.setChunkEmbeddings(cEmbs);
                m.setChunkTexts(cTexts);
                timestamps.put(m.getId(), lastMod);
                if (!useDisk) {
                    cache.put(m.getId(), new CacheEntry(lastMod, emb, cEmbs));
                }
                count++;
                throttle();
            }
        }

        if (count > 0) {
            log.info("Computed {} new embeddings in background", count);
            persistCache();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Initialises cache directory and loads persisted caches from disk.
     * Safe to call multiple times — runs only once per instance.
     */
    private void initCache() {
        if (cacheInitialised || rootDir == null) return;
        cacheInitialised = true;

        Path memolinkDir = rootDir.resolve(".memolink");
        try {
            Files.createDirectories(memolinkDir);
            cacheFile      = memolinkDir.resolve("embeddings.json");
            timestampsFile = memolinkDir.resolve("timestamps.json");
        } catch (IOException e) {
            log.warn("Could not create .memolink directory: {}", e.getMessage());
            return;
        }

        loadTimestamps();
        if (!useDisk) {
            loadEmbeddings();
        }
    }

    private void loadTimestamps() {
        if (timestampsFile == null || !Files.exists(timestampsFile)) return;
        try {
            Map<String, Long> loaded = mapper.readValue(
                    timestampsFile.toFile(), new TypeReference<>() {});
            if (loaded != null) timestamps.putAll(loaded);
        } catch (IOException e) {
            log.warn("Failed to read timestamps cache: {}", e.getMessage());
        }
    }

    private void loadEmbeddings() {
        if (cacheFile == null || !Files.exists(cacheFile)) return;
        try {
            Map<String, CacheEntry> loaded = mapper.readValue(
                    cacheFile.toFile(), new TypeReference<>() {});
            if (loaded != null) {
                cache.putAll(loaded);
                log.info("Loaded {} cached embeddings", cache.size());
            }
        } catch (IOException e) {
            log.warn("Failed to read embeddings cache, will rebuild: {}", e.getMessage());
        }
    }

    private void embedAll(List<MdFileMetadata> files, EmbeddingService embeddingService) {
        if (embeddingService == null || !embeddingService.isAvailable()) return;
        initCache();

        for (MdFileMetadata m : files) {
            if (m.hasEmbedding() && m.getChunkEmbeddings() != null) continue;

            long lastMod    = readLastModified(m.getFilePath());
            CacheEntry entry = cache.get(m.getId());

            if (entry != null && entry.lastModified() >= lastMod
                    && entry.embedding() != null && entry.chunkEmbeddings() != null) {
                m.setEmbedding(entry.embedding());
                m.setChunkEmbeddings(entry.chunkEmbeddings());
                // Reconstruct chunk texts (not stored in cache to keep file small)
                m.setChunkTexts(splitParagraphs(m.getContent()));
            }
        }
    }

    private void persistCache() {
        if (!useDisk && cacheFile != null) {
            try {
                mapper.writeValue(cacheFile.toFile(), cache);
                log.info("Saved embeddings cache ({} entries)", cache.size());
            } catch (IOException e) {
                log.warn("Failed to write embeddings cache: {}", e.getMessage());
            }
        }
        if (timestampsFile != null) {
            try {
                mapper.writeValue(timestampsFile.toFile(), timestamps);
                log.info("Saved timestamps cache ({} entries)", timestamps.size());
            } catch (IOException e) {
                log.warn("Failed to write timestamps cache: {}", e.getMessage());
            }
        }
    }

    /**
     * Splits content into non-empty paragraphs of at least {@value #MIN_CHUNK_LENGTH} chars.
     */
    private static List<String> splitParagraphs(String content) {
        if (content == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String para : content.split("\\n\\s*\\n")) {
            String clean = para.trim();
            if (clean.length() >= MIN_CHUNK_LENGTH) result.add(clean);
        }
        return result;
    }

    /**
     * Returns a vault-relative file ID (forward slashes) for the given absolute path.
     */
    private String toFileId(Path path) {
        return rootDir != null
                ? rootDir.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                : path.getFileName().toString();
    }

    private static long readLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void throttle() {
        try {
            Thread.sleep(100); // Throttle to prevent sustained CPU pressure during background embedding
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
