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

/**
 * Lucene-backed search with two modes:
 *
 * <h3>Keyword search (BM25)</h3>
 * Fields: title×3, headings×2, tags×2, content×1
 *
 * <h3>Semantic search (KNN)</h3>
 * If an {@link EmbeddingService} is provided and notes have embeddings,
 * a {@code KnnFloatVectorQuery} is run over the {@code embedding} field.
 *
 * <h3>Hybrid search</h3>
 * {@link #hybridSearch(String, EmbeddingService, int)} combines both scores:
 * {@code final_score = α * normalised_bm25 + β * knn_score}
 * where α=0.6, β=0.4 by default. This is the main entry-point for agents.
 */
public class GraphSearchService implements Closeable {

    private static final float ALPHA = 0.2f;  // BM25 weight
    private static final float BETA  = 0.8f;  // KNN weight

    private final boolean          useDisk;
    private final Directory        directory;
    private final Directory        chunkDirectory;
    private final StandardAnalyzer analyzer;

    private DirectoryReader reader;
    private IndexSearcher   searcher;

    private DirectoryReader chunkReader;
    private IndexSearcher   chunkSearcher;

    public GraphSearchService() {
        this(false, null);
    }

    public GraphSearchService(boolean useDisk, Path indexDir) {
        this.useDisk = useDisk;
        this.analyzer  = new StandardAnalyzer();
        try {
            if (useDisk && indexDir != null) {
                if (!Files.exists(indexDir)) Files.createDirectories(indexDir);
                this.directory = FSDirectory.open(indexDir.resolve("main"));
                this.chunkDirectory = FSDirectory.open(indexDir.resolve("chunks"));
            } else {
                this.directory = new ByteBuffersDirectory();
                this.chunkDirectory = new ByteBuffersDirectory();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Lucene directories", e);
        }
    }

    public record ChunkSearchResult(String fileId, int chunkIndex, float score) {}

    // ── Indexing ──────────────────────────────────────────────────────────────

    public void index(Collection<MdFileMetadata> notes) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(useDisk ? IndexWriterConfig.OpenMode.CREATE_OR_APPEND : IndexWriterConfig.OpenMode.CREATE);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (MdFileMetadata note : notes) {
                if (useDisk && !note.isModified()) {
                    continue; // Skip safely because Lucene already has it on disk
                }
                
                Document doc = new Document();
                doc.add(new StringField("id",       note.getId(),    Field.Store.YES));
                doc.add(new TextField("title",      note.getTitle(), Field.Store.YES));
                doc.add(new TextField("content",    note.getContent(), Field.Store.NO));
                doc.add(new TextField("tags",       String.join(" ", note.getTags()),     Field.Store.NO));
                doc.add(new TextField("headings",   String.join(" ", note.getHeadings()), Field.Store.NO));
                // Index folder path segments so agents can search by folder/directory name.
                // e.g. note ID "skills/java/spring-ai.md" → folder field "skills java"
                String noteId = note.getId();
                int lastSlash = noteId.lastIndexOf('/');
                if (lastSlash > 0) {
                    String folderPath = noteId.substring(0, lastSlash)
                            .replace('/', ' ')
                            .replace('-', ' ');
                    doc.add(new TextField("folder", folderPath, Field.Store.NO));
                }
                // Metadata ranking boost stored for retrieval
                doc.add(new StoredField("importance",   note.getImportance()));
                doc.add(new StoredField("accessCount",  note.getAccessCount()));
                // KNN vector field (only when embedding is available)
                if (note.getEmbedding() != null) {
                    doc.add(new KnnFloatVectorField("embedding", note.getEmbedding(), VectorSimilarityFunction.COSINE));
                }
                writer.updateDocument(new Term("id", note.getId()), doc);
            }
            writer.commit();
        }

        IndexWriterConfig chunkConfig = new IndexWriterConfig(analyzer);
        chunkConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try (IndexWriter chunkWriter = new IndexWriter(chunkDirectory, chunkConfig)) {
            for (MdFileMetadata note : notes) {
                if (note.getChunkEmbeddings() != null && note.getChunkTexts() != null) {
                    // Since multiple chunks share the same fileId, we use a compound term for deletion if we were to update chunks individually.
                    // However, since we replace the entire file, we should delete all existing chunks for this file first.
                    chunkWriter.deleteDocuments(new Term("fileId", note.getId()));
                    for (int i = 0; i < note.getChunkEmbeddings().size(); i++) {
                        Document cDoc = new Document();
                        cDoc.add(new StringField("fileId", note.getId(), Field.Store.YES));
                        cDoc.add(new StoredField("chunkIndex", i));
                        cDoc.add(new KnnFloatVectorField("chunk_embedding", note.getChunkEmbeddings().get(i), VectorSimilarityFunction.COSINE));
                        chunkWriter.addDocument(cDoc);
                    }
                }
            }
            chunkWriter.commit();
        }

        this.reader   = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);

        this.chunkReader   = DirectoryReader.open(chunkDirectory);
        this.chunkSearcher = new IndexSearcher(chunkReader);
    }

    public void deleteFromIndex(String fileId) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            writer.deleteDocuments(new Term("id", fileId));
            writer.commit();
        }

        IndexWriterConfig chunkConfig = new IndexWriterConfig(analyzer);
        chunkConfig.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
        try (IndexWriter chunkWriter = new IndexWriter(chunkDirectory, chunkConfig)) {
            chunkWriter.deleteDocuments(new Term("fileId", fileId));
            chunkWriter.commit();
        }

        // Reopen readers
        DirectoryReader newReader = DirectoryReader.openIfChanged(this.reader);
        if (newReader != null) {
            this.reader.close();
            this.reader = newReader;
            this.searcher = new IndexSearcher(this.reader);
        }

        DirectoryReader newChunkReader = DirectoryReader.openIfChanged(this.chunkReader);
        if (newChunkReader != null) {
            this.chunkReader.close();
            this.chunkReader = newChunkReader;
            this.chunkSearcher = new IndexSearcher(this.chunkReader);
        }
    }

    // ── Keyword search (BM25) ─────────────────────────────────────────────────

    /** Returns IDs only (backwards-compatible). */
    public List<String> search(String query, int maxResults) throws IOException {
        return searchWithScores(query, maxResults).stream()
                .map(SearchResult::id)
                .toList();
    }

    public List<SearchResult> searchWithScores(String query, int maxResults) throws IOException {
        if (reader == null || query == null || query.isBlank()) return Collections.emptyList();
        Map<String, Float> boosts = Map.of(
            "title",    3.0f,
            "headings", 2.0f,
            "tags",     2.0f,
            "folder",   2.0f,
            "content",  1.0f
        );
        String[] fields = {"title", "headings", "tags", "folder", "content"};
        Query parsedQuery = buildKeywordQuery(query, fields, boosts);
        TopDocs topDocs = searcher.search(parsedQuery, maxResults);
        List<SearchResult> results = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(sd.doc);
            results.add(new SearchResult(doc.get("id"), doc.get("title"), sd.score));
        }
        return results;
    }

    // ── Semantic search (KNN) ─────────────────────────────────────────────────

    /**
     * Pure KNN vector search using a query embedding.
     * Returns empty list if embeddings were not indexed.
     */
    public List<SearchResult> semanticSearch(float[] queryEmbedding, int maxResults) throws IOException {
        if (reader == null || queryEmbedding == null) return Collections.emptyList();
        Query knnQuery = new KnnFloatVectorQuery("embedding", queryEmbedding, maxResults);
        TopDocs topDocs = searcher.search(knnQuery, maxResults);
        List<SearchResult> results = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(sd.doc);
            results.add(new SearchResult(doc.get("id"), doc.get("title"), sd.score));
        }
        return results;
    }

    public List<ChunkSearchResult> searchChunks(float[] queryEmbedding, int maxResults) throws IOException {
        if (chunkReader == null || queryEmbedding == null) return Collections.emptyList();
        Query knnQuery = new KnnFloatVectorQuery("embedding", queryEmbedding, maxResults);
        TopDocs topDocs = chunkSearcher.search(knnQuery, maxResults);
        List<ChunkSearchResult> results = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = chunkSearcher.storedFields().document(sd.doc);
            results.add(new ChunkSearchResult(doc.get("fileId"), doc.getField("chunkIndex").numericValue().intValue(), sd.score));
        }
        return results;
    }

    // ── Hybrid search (BM25 + KNN + metadata ranking) ────────────────────────

    /**
     * Combines BM25, KNN semantic score, importance and recency into one score:
     * <pre>
     * final = α*norm(bm25) + β*knn + γ*importance_norm + δ*recency_norm
     * </pre>
     * Pass a {@code metadataLookup} (e.g. {@code graph::getMdFile}) to enable
     * importance+recency boosting; pass {@code null} to skip it.
     */
    public List<SearchResult> hybridSearch(String query,
                                           EmbeddingService embeddingService,
                                           int maxResults) throws IOException {
        return hybridSearch(query, embeddingService, maxResults, null);
    }

    public List<SearchResult> hybridSearch(String query,
                                           EmbeddingService embeddingService,
                                           int maxResults,
                                           Function<String, MdFileMetadata> metadataLookup)
            throws IOException {
        if (reader == null || query == null || query.isBlank()) return Collections.emptyList();

        // BM25 pass
        List<SearchResult> keyword = searchWithScores(query, maxResults * 2);

        // Semantic pass
        List<SearchResult> semantic = Collections.emptyList();
        if (embeddingService != null && embeddingService.isAvailable()) {
            float[] qEmb = embeddingService.embed(query);
            if (qEmb != null) semantic = semanticSearch(qEmb, maxResults * 2);
        }

        // Merge BM25 + KNN scores
        Map<String, Float> scores = new LinkedHashMap<>();
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

        // Metadata ranking boost (Capability 5)
        if (metadataLookup != null) {
            long now = System.currentTimeMillis();
            long oneWeekMs = 7L * 24 * 3600 * 1000;
            for (String id : scores.keySet()) {
                MdFileMetadata m = metadataLookup.apply(id);
                if (m == null) continue;
                // Importance: 0-10 → 0-0.10 boost
                float importanceBoost = m.getImportance() * 0.01f;
                // Recency: decays from 0.05 to 0 over one week since last access
                float recencyBoost = 0f;
                long lastAccess = m.getLastAccessedMs();
                if (lastAccess > 0) {
                    long ageMs = now - lastAccess;
                    recencyBoost = Math.max(0f, 0.05f * (1f - (float) ageMs / oneWeekMs));
                }
                scores.merge(id, importanceBoost + recencyBoost, Float::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(maxResults)
                .map(e -> new SearchResult(e.getKey(), titles.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .toList();
    }

    // ── Private ───────────────────────────────────────────────────────────────

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

    @Override
    public void close() throws IOException {
        if (reader != null) { reader.close(); }
        if (chunkReader != null) { chunkReader.close(); }
        analyzer.close();
        directory.close();
        chunkDirectory.close();
    }
}
