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

import java.io.Closeable;
import java.io.IOException;
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

    private final Directory        directory;
    private final StandardAnalyzer analyzer;

    private DirectoryReader reader;
    private IndexSearcher   searcher;

    public GraphSearchService() {
        this.directory = new ByteBuffersDirectory();
        this.analyzer  = new StandardAnalyzer();
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

    public void index(Collection<MdFileMetadata> notes) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (MdFileMetadata note : notes) {
                Document doc = new Document();
                doc.add(new StringField("id",       note.getId(),    Field.Store.YES));
                doc.add(new TextField("title",      note.getTitle(), Field.Store.YES));
                doc.add(new TextField("content",    note.getContent(), Field.Store.NO));
                doc.add(new TextField("tags",       String.join(" ", note.getTags()),     Field.Store.NO));
                doc.add(new TextField("headings",   String.join(" ", note.getHeadings()), Field.Store.NO));
                // Metadata ranking boost stored for retrieval
                doc.add(new StoredField("importance",   note.getImportance()));
                doc.add(new StoredField("accessCount",  note.getAccessCount()));
                // KNN vector field (only when embedding is available)
                if (note.hasEmbedding()) {
                    doc.add(new KnnFloatVectorField("embedding", note.getEmbedding(),
                            VectorSimilarityFunction.COSINE));
                }
                writer.addDocument(doc);
            }
        }
        this.reader   = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);
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
            "content",  1.0f
        );
        String[] fields = {"title", "headings", "tags", "content"};
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
        analyzer.close();
        directory.close();
    }
}
