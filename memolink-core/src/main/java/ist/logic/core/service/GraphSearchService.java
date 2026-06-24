package ist.logic.core.service;

import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.SearchResult;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lucene-backed search engine with three modes:
 *
 * <h3>Keyword search (BM25)</h3>
 * Fields: title×3, headings×2, tags×2, folder×2, content×1
 *
 * <h3>Semantic search (KNN)</h3>
 * Uses {@code KnnFloatVectorQuery} over the {@code embedding} field when
 * an {@link EmbeddingService} is available and memories have embeddings.
 *
 * <h3>Hybrid search</h3>
 * {@link #hybridSearch} combines BM25 + KNN + importance + recency:
 * {@code score = α*norm(bm25) + β*knn + importance_boost + recency_boost}
 * where α=0.2, β=0.8.
 *
 * <h3>Storage modes</h3>
 * <ul>
 *   <li><b>Memory mode</b> (default) — {@code ByteBuffersDirectory}, rebuilt on every start.</li>
 *   <li><b>Disk mode</b> — {@code FSDirectory} under {@code .memolink/lucene/},
 *       persistent and incrementally updated.</li>
 * </ul>
 */
public class GraphSearchService implements Closeable {

    private static final float ALPHA = 0.2f; // BM25 weight in hybrid score
    private static final float BETA  = 0.8f; // KNN weight in hybrid score

    private final boolean          useDisk;
    private final Directory        directory;
    private final Directory        chunkDirectory;
    private final StandardAnalyzer analyzer;

    private DirectoryReader reader;
    private IndexSearcher   searcher;
    private DirectoryReader chunkReader;
    private IndexSearcher   chunkSearcher;

    /** Result of a chunk-level KNN search used by {@link #searchChunks}. */
    public record ChunkSearchResult(String fileId, int chunkIndex, float score) {}

    public GraphSearchService() {
        this(false, null);
    }

    public GraphSearchService(boolean useDisk, Path indexDir) {
        this.useDisk  = useDisk;
        this.analyzer = new StandardAnalyzer();
        try {
            if (useDisk && indexDir != null) {
                Files.createDirectories(indexDir);
                this.directory      = FSDirectory.open(indexDir.resolve("main"));
                this.chunkDirectory = FSDirectory.open(indexDir.resolve("chunks"));
            } else {
                this.directory      = new ByteBuffersDirectory();
                this.chunkDirectory = new ByteBuffersDirectory();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise Lucene directories", e);
        }
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

    /**
     * Indexes (or re-indexes) the given collection of memories.
     * In disk mode, only memories whose {@code modified} flag is set are updated.
     */
    public void index(Collection<MdFileMetadata> memories) throws IOException {
        IndexWriterConfig.OpenMode mode = useDisk
                ? IndexWriterConfig.OpenMode.CREATE_OR_APPEND
                : IndexWriterConfig.OpenMode.CREATE;

        try (IndexWriter writer = openWriter(directory, mode)) {
            for (MdFileMetadata memory : memories) {
                if (useDisk && !memory.isModified()) continue;
                writer.updateDocument(new Term("id", memory.getId()), buildDocument(memory));
            }
            writer.commit();
        }

        IndexWriterConfig.OpenMode chunkMode = useDisk
                ? IndexWriterConfig.OpenMode.CREATE_OR_APPEND
                : IndexWriterConfig.OpenMode.CREATE;
        try (IndexWriter chunkWriter = openWriter(chunkDirectory, chunkMode)) {
            for (MdFileMetadata memory : memories) {
                if (useDisk && !memory.isModified()) continue;
                if (memory.getChunkEmbeddings() != null && memory.getChunkTexts() != null) {
                    chunkWriter.deleteDocuments(new Term("fileId", memory.getId()));
                    for (int i = 0; i < memory.getChunkEmbeddings().size(); i++) {
                        Document cDoc = new Document();
                        cDoc.add(new StringField("fileId", memory.getId(), Field.Store.YES));
                        cDoc.add(new StoredField("chunkIndex", i));
                        cDoc.add(new KnnFloatVectorField("embedding",
                                memory.getChunkEmbeddings().get(i), VectorSimilarityFunction.COSINE));
                        chunkWriter.addDocument(cDoc);
                    }
                }
            }
            chunkWriter.commit();
        }

        reopenReaders();
    }

    /**
     * Removes a memory from both the main and chunk indexes (disk mode only).
     */
    public void deleteFromIndex(String fileId) throws IOException {
        try (IndexWriter writer = openWriter(directory, IndexWriterConfig.OpenMode.APPEND)) {
            writer.deleteDocuments(new Term("id", fileId));
            writer.commit();
        }
        try (IndexWriter chunkWriter = openWriter(chunkDirectory, IndexWriterConfig.OpenMode.APPEND)) {
            chunkWriter.deleteDocuments(new Term("fileId", fileId));
            chunkWriter.commit();
        }
        reopenReaders();
    }

    // ── Keyword search (BM25) ─────────────────────────────────────────────────

    /** Returns matching memory IDs only (score-less, backwards-compatible). */
    public List<String> search(String query, int maxResults) throws IOException {
        return searchWithScores(query, maxResults).stream().map(SearchResult::id).toList();
    }

    public List<SearchResult> searchWithScores(String query, int maxResults) throws IOException {
        if (!isReady() || isBlank(query)) return Collections.emptyList();
        Map<String, Float> boosts = Map.of(
                "title",    3.0f,
                "headings", 2.0f,
                "tags",     2.0f,
                "folder",   2.0f,
                "content",  1.0f);
        String[] fields = {"title", "headings", "tags", "folder", "content"};
        TopDocs topDocs = searcher.search(buildKeywordQuery(query, fields, boosts), maxResults);
        return scoreDocs(topDocs, searcher, doc -> new SearchResult(doc.get("id"), doc.get("title"), 0));
    }

    // ── Semantic search (KNN) ─────────────────────────────────────────────────

    /** Pure KNN vector search. Returns empty list if embeddings are not yet indexed. */
    public List<SearchResult> semanticSearch(float[] queryEmbedding, int maxResults) throws IOException {
        if (!isReady() || queryEmbedding == null) return Collections.emptyList();
        Query knnQuery = new KnnFloatVectorQuery("embedding", queryEmbedding, maxResults);
        TopDocs topDocs = searcher.search(knnQuery, maxResults);
        return scoreDocs(topDocs, searcher, doc -> new SearchResult(doc.get("id"), doc.get("title"), 0));
    }

    /**
     * Chunk-level KNN search used by {@code ask_vault}.
     * Results are returned in KNN cosine-similarity order.
     */
    public List<ChunkSearchResult> searchChunks(float[] queryEmbedding, int maxResults) throws IOException {
        return searchChunks(queryEmbedding, maxResults, null, null, null);
    }

    /**
     * Chunk-level KNN search with optional cross-encoder reranking.
     *
     * <p>When {@code reranker} is non-null and available, retrieves
     * {@code maxResults * candidateMultiple} initial candidates via KNN and
     * reranks them with the cross-encoder, returning the top {@code maxResults}.
     *
     * @param queryEmbedding   KNN query vector
     * @param maxResults       number of final results to return
     * @param originalQuery    plain-text query used for cross-encoder scoring (null = skip rerank)
     * @param reranker         optional cross-encoder service; null = KNN order kept
     * @param chunkTextSupplier maps a ChunkSearchResult to its passage text for scoring
     */
    public List<ChunkSearchResult> searchChunks(float[] queryEmbedding,
                                                 int maxResults,
                                                 String originalQuery,
                                                 CrossEncoderService reranker,
                                                 Function<ChunkSearchResult, String> chunkTextSupplier)
            throws IOException {
        if (chunkReader == null || queryEmbedding == null) return Collections.emptyList();

        boolean doRerank = reranker != null && reranker.isAvailable()
                           && originalQuery != null && chunkTextSupplier != null;
        int fetchCount = doRerank ? maxResults * 4 : maxResults;

        Query knnQuery = new KnnFloatVectorQuery("embedding", queryEmbedding, fetchCount);
        TopDocs topDocs = chunkSearcher.search(knnQuery, fetchCount);
        List<ChunkSearchResult> results = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = chunkSearcher.storedFields().document(sd.doc);
            results.add(new ChunkSearchResult(
                    doc.get("fileId"),
                    doc.getField("chunkIndex").numericValue().intValue(),
                    sd.score));
        }

        if (doRerank) {
            results = reranker.rerank(originalQuery, results, chunkTextSupplier,
                    (c, s) -> new ChunkSearchResult(c.fileId(), c.chunkIndex(), s),
                    maxResults);
        }
        return results;
    }

    // ── Hybrid search (BM25 + KNN + metadata ranking) ────────────────────────

    /**
     * Combines BM25, KNN, importance, and recency into a single ranking:
     * <pre>score = α*norm(bm25) + β*norm(knn) + importance_boost + recency_boost</pre>
     * Pass a {@code metadataLookup} to enable importance/recency boosting, or
     * {@code null} to skip it.
     */
    /** Hybrid search without metadata boosting and without reranking. */
    public List<SearchResult> hybridSearch(String query, EmbeddingService embeddingService,
                                           int maxResults) throws IOException {
        return hybridSearch(query, embeddingService, maxResults, null, null);
    }

    /** Hybrid search with metadata boosting but without reranking. */
    public List<SearchResult> hybridSearch(String query,
                                           EmbeddingService embeddingService,
                                           int maxResults,
                                           Function<String, MdFileMetadata> metadataLookup)
            throws IOException {
        return hybridSearch(query, embeddingService, maxResults, metadataLookup, null);
    }

    /**
     * Full hybrid search with optional metadata boosting AND optional cross-encoder reranking.
     *
     * <p>When {@code reranker} is non-null and available:
     * <ol>
     *   <li>Retrieves {@code maxResults * 4} candidates via BM25+KNN+metadata fusion.</li>
     *   <li>Re-scores each candidate as {@code (query, title + body)} via the cross-encoder.</li>
     *   <li>Returns the top {@code maxResults} reranked results.</li>
     * </ol>
     *
     * @param reranker      optional cross-encoder; null means heuristic fusion order is kept
     * @param bodyLookup    supplies passage text for reranking; ignored when reranker is null
     */
    public List<SearchResult> hybridSearch(String query,
                                           EmbeddingService embeddingService,
                                           int maxResults,
                                           Function<String, MdFileMetadata> metadataLookup,
                                           CrossEncoderService reranker)
            throws IOException {
        if (!isReady() || isBlank(query)) return Collections.emptyList();

        boolean doRerank = reranker != null && reranker.isAvailable();
        int fetchCount = doRerank ? maxResults * 4 : maxResults;

        List<SearchResult> keyword  = searchWithScores(query, fetchCount * 2);
        List<SearchResult> semantic = Collections.emptyList();
        if (embeddingService != null && embeddingService.isAvailable()) {
            float[] qEmb = embeddingService.embed(query);
            if (qEmb != null) semantic = semanticSearch(qEmb, fetchCount * 2);
        }

        Map<String, Float>  scores = new LinkedHashMap<>();
        Map<String, String> titles = new HashMap<>();

        float maxBm25 = keyword.stream().map(SearchResult::score).max(Float::compare).orElse(1f);
        for (SearchResult r : keyword) {
            scores.merge(r.id(), ALPHA * (r.score() / maxBm25), Float::sum);
            titles.put(r.id(), r.title());
        }
        if (!semantic.isEmpty()) {
            float maxKnn = semantic.stream().map(SearchResult::score).max(Float::compare).orElse(1f);
            for (SearchResult r : semantic) {
                scores.merge(r.id(), BETA * (r.score() / maxKnn), Float::sum);
                titles.putIfAbsent(r.id(), r.title());
            }
        }

        if (metadataLookup != null) {
            applyMetadataBoost(scores, metadataLookup);
        }

        List<SearchResult> fused = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(fetchCount)
                .map(e -> new SearchResult(e.getKey(), titles.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .toList();

        if (doRerank && metadataLookup != null) {
            return reranker.rerank(query, fused,
                    r -> {
                        MdFileMetadata m = metadataLookup.apply(r.id());
                        return m == null ? r.title()
                                : r.title() + " " + m.getContent().substring(
                                        0, Math.min(m.getContent().length(), 512));
                    },
                    (r, s) -> new SearchResult(r.id(), r.title(), s),
                    maxResults);
        }
        return fused.size() <= maxResults ? fused : fused.subList(0, maxResults);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() throws IOException {
        if (reader != null)      reader.close();
        if (chunkReader != null) chunkReader.close();
        analyzer.close();
        directory.close();
        chunkDirectory.close();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Document buildDocument(MdFileMetadata memory) {
        Document doc = new Document();
        doc.add(new StringField("id",       memory.getId(),    Field.Store.YES));
        doc.add(new TextField("title",      memory.getTitle(), Field.Store.YES));
        doc.add(new TextField("content",    memory.getContent(), Field.Store.NO));
        doc.add(new TextField("tags",       String.join(" ", memory.getTags()),     Field.Store.NO));
        doc.add(new TextField("headings",   String.join(" ", memory.getHeadings()), Field.Store.NO));

        // Index folder path segments so agents can search by directory name
        // e.g. "skills/java/spring-ai.md" → folder field "skills java"
        String id       = memory.getId();
        int    lastSlash = id.lastIndexOf('/');
        if (lastSlash > 0) {
            String folder = id.substring(0, lastSlash).replace('/', ' ').replace('-', ' ');
            doc.add(new TextField("folder", folder, Field.Store.NO));
        }

        doc.add(new StoredField("importance",  memory.getImportance()));
        doc.add(new StoredField("accessCount", memory.getAccessCount()));

        if (memory.getEmbedding() != null) {
            doc.add(new KnnFloatVectorField("embedding", memory.getEmbedding(),
                    VectorSimilarityFunction.COSINE));
        }
        return doc;
    }

    private void applyMetadataBoost(Map<String, Float> scores,
                                    Function<String, MdFileMetadata> metadataLookup) {
        long now       = System.currentTimeMillis();
        long oneWeekMs = 7L * 24 * 3600 * 1000;
        for (String id : scores.keySet()) {
            MdFileMetadata m = metadataLookup.apply(id);
            if (m == null) continue;
            float importanceBoost = m.getImportance() * 0.01f; // 0–10 → 0–0.10
            float recencyBoost    = 0f;
            long  lastAccess      = m.getLastAccessedMs();
            if (lastAccess > 0) {
                long ageMs = now - lastAccess;
                recencyBoost = Math.max(0f, 0.05f * (1f - (float) ageMs / oneWeekMs));
            }
            scores.merge(id, importanceBoost + recencyBoost, Float::sum);
        }
    }

    private <T> List<T> scoreDocs(TopDocs topDocs, IndexSearcher s,
                                   java.util.function.Function<Document, T> mapper) throws IOException {
        List<T> results = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = s.storedFields().document(sd.doc);
            // Re-apply the real score to SearchResult using the raw score from ScoreDoc
            T item = mapper.apply(doc);
            if (item instanceof SearchResult sr) {
                results.add((T) new SearchResult(sr.id(), sr.title(), sd.score));
            } else {
                results.add(item);
            }
        }
        return results;
    }

    private void reopenReaders() throws IOException {
        if (reader == null) {
            this.reader   = DirectoryReader.open(directory);
            this.searcher = new IndexSearcher(reader);
        } else {
            DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
            if (newReader != null) {
                reader.close();
                reader   = newReader;
                searcher = new IndexSearcher(reader);
            }
        }

        if (chunkReader == null) {
            this.chunkReader   = DirectoryReader.open(chunkDirectory);
            this.chunkSearcher = new IndexSearcher(chunkReader);
        } else {
            DirectoryReader newChunkReader = DirectoryReader.openIfChanged(chunkReader);
            if (newChunkReader != null) {
                chunkReader.close();
                chunkReader   = newChunkReader;
                chunkSearcher = new IndexSearcher(chunkReader);
            }
        }
    }

    private static IndexWriter openWriter(Directory dir, IndexWriterConfig.OpenMode mode)
            throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(mode);
        return new IndexWriter(dir, config);
    }

    private Query buildKeywordQuery(String query, String[] fields, Map<String, Float> boosts) {
        MultiFieldQueryParser qp = new MultiFieldQueryParser(fields, analyzer, boosts);
        qp.setDefaultOperator(MultiFieldQueryParser.Operator.AND);
        try {
            return qp.parse(query);
        } catch (ParseException ignored) {}
        try {
            return qp.parse(MultiFieldQueryParser.escape(query));
        } catch (ParseException e) {
            return new BooleanQuery.Builder().build();
        }
    }

    private boolean isReady()          { return reader != null; }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
