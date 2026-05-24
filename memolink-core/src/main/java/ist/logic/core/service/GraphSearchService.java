package ist.logic.core.service;

import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.SearchResult;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/**
 * Lucene-backed full-text search over note content.
 *
 * Fields indexed (with boost applied at query time):
 *   title    ×3
 *   headings ×2
 *   tags     ×2
 *   content  ×1
 *
 * Query strategy: tries the raw query first; if parsing fails (special chars),
 * falls back to {@link MultiFieldQueryParser#escape} so the search never
 * silently returns nothing due to a parse error.
 *
 * Uses an in-memory {@link ByteBuffersDirectory}.
 * Call {@link #index(Collection)} once before {@link #search(String, int)}.
 */
public class GraphSearchService implements Closeable {

    private final Directory        directory;
    private final StandardAnalyzer analyzer;

    private DirectoryReader reader;
    private IndexSearcher   searcher;

    public GraphSearchService() {
        this.directory = new ByteBuffersDirectory();
        this.analyzer  = new StandardAnalyzer();
    }

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
                writer.addDocument(doc);
            }
        }
        this.reader   = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);
    }

    /** Returns IDs only (backwards-compatible). */
    public List<String> search(String query, int maxResults) throws IOException {
        return searchWithScores(query, maxResults).stream()
                .map(SearchResult::id)
                .toList();
    }

    /**
     * Returns ranked {@link SearchResult} objects that include the Lucene score
     * and note title, allowing callers to filter low-confidence results.
     */
    public List<SearchResult> searchWithScores(String query, int maxResults) throws IOException {
        if (reader == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        Map<String, Float> boosts = Map.of(
            "title",    3.0f,
            "headings", 2.0f,
            "tags",     2.0f,
            "content",  1.0f
        );
        String[] fields = {"title", "headings", "tags", "content"};

        Query parsedQuery = buildQuery(query, fields, boosts);

        TopDocs topDocs = searcher.search(parsedQuery, maxResults);
        List<SearchResult> results = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            results.add(new SearchResult(doc.get("id"), doc.get("title"), scoreDoc.score));
        }
        return results;
    }

    /**
     * Build a query that honours phrase intent.
     * <ol>
     *   <li>Try the raw query string (supports phrases like {@code "spring boot"}).</li>
     *   <li>If parsing fails due to special characters, escape and retry.</li>
     * </ol>
     */
    private Query buildQuery(String query,
                             String[] fields,
                             Map<String, Float> boosts) {
        MultiFieldQueryParser qp = new MultiFieldQueryParser(fields, analyzer, boosts);
        qp.setDefaultOperator(MultiFieldQueryParser.Operator.AND);

        // 1. Try raw query (preserves phrase intent: "spring boot config")
        try {
            return qp.parse(query);
        } catch (ParseException ignored) {
            // fall through
        }

        // 2. Fall back to escaped query (handles stray +/-/: characters)
        try {
            return qp.parse(MultiFieldQueryParser.escape(query));
        } catch (ParseException e) {
            // Should never happen after escaping, but guard anyway
            return new BooleanQuery.Builder().build(); // empty → no results
        }
    }

    @Override
    public void close() throws IOException {
        if (reader != null) { reader.close(); }
        analyzer.close();
        directory.close();
    }
}
