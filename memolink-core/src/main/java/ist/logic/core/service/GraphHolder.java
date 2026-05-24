package ist.logic.core.service;

import ist.logic.core.model.KnowledgeGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe holder for the live {@link KnowledgeGraph} and its paired
 * {@link GraphSearchService}.
 *
 * <p>Both references are swapped atomically via a single {@code volatile}
 * write to an immutable {@link Snapshot} record, so callers always see a
 * consistent graph/search pair — never a mix of old graph with new index.
 *
 * <p>The previous {@link GraphSearchService} is closed after each swap to
 * release its in-memory Lucene directory and reader.
 *
 * <p>Framework-agnostic — no Spring dependency.
 */
public class GraphHolder {

    private static final Logger log = LoggerFactory.getLogger(GraphHolder.class);

    private record Snapshot(KnowledgeGraph graph, GraphSearchService searchService) {}

    private volatile Snapshot snapshot;

    public GraphHolder(KnowledgeGraph graph, GraphSearchService searchService) {
        this.snapshot = new Snapshot(graph, searchService);
    }

    public KnowledgeGraph getGraph() {
        return snapshot.graph();
    }

    public GraphSearchService getSearchService() {
        return snapshot.searchService();
    }

    /**
     * Atomically replace both graph and search index with rebuilt versions,
     * then close the previous {@link GraphSearchService} to free memory.
     */
    public void update(KnowledgeGraph graph, GraphSearchService searchService) {
        Snapshot old = this.snapshot;
        this.snapshot = new Snapshot(graph, searchService);
        try {
            old.searchService().close();
        } catch (Exception e) {
            log.warn("Failed to close old GraphSearchService: {}", e.getMessage());
        }
    }
}
