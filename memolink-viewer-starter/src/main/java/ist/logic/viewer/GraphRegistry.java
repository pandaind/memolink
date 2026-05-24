package ist.logic.viewer;

import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.service.GraphSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry that:
 * <ul>
 *   <li>holds the current {@link KnowledgeGraph} and {@link GraphSearchService}
 *       as a single atomically-swappable pair (via a {@code volatile} snapshot
 *       record), so readers never see a mismatched graph/index combination;</li>
 *   <li>manages a list of active {@link SseEmitter}s and broadcasts a
 *       {@code graph-updated} Server-Sent Event to every connected browser
 *       client whenever the graph is rebuilt by {@link ist.logic.core.service.GraphWatchService}.</li>
 * </ul>
 */
public class GraphRegistry {

    private static final Logger log = LoggerFactory.getLogger(GraphRegistry.class);

    private record Snapshot(KnowledgeGraph graph, GraphSearchService searchService) {}

    private volatile Snapshot                snapshot;
    private final    List<SseEmitter>        emitters = new CopyOnWriteArrayList<>();

    public GraphRegistry(KnowledgeGraph graph, GraphSearchService searchService) {
        this.snapshot = new Snapshot(graph, searchService);
    }

    public KnowledgeGraph getGraph() {
        return snapshot.graph();
    }

    public GraphSearchService getSearchService() {
        return snapshot.searchService();
    }

    /**
     * Atomically swaps both the graph and search index, then pushes a
     * {@code graph-updated} SSE event to all connected browser clients.
     */
    public void update(KnowledgeGraph newGraph, GraphSearchService newSearch) {
        this.snapshot = new Snapshot(newGraph, newSearch);
        notifyClients();
    }

    /**
     * Registers a new SSE subscriber.  The emitter is automatically removed
     * when the connection closes, times out, or errors.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e       -> emitters.remove(emitter));
        return emitter;
    }

    private void notifyClients() {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("graph-updated").data(""));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
        log.debug("Pushed graph-updated event to {} client(s)", emitters.size());
    }
}
