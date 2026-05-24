package ist.logic.viewer.controller;

import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.viewer.GraphRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class GraphController {

    private final GraphRegistry         registry;
    private final GraphTraversalService traversalService;

    public GraphController(GraphRegistry registry,
                           GraphTraversalService traversalService) {
        this.registry         = registry;
        this.traversalService = traversalService;
    }

    /** Full graph JSON: nodes + edges. */
    @GetMapping("/graph")
    public KnowledgeGraph getGraph() {
        return registry.getGraph();
    }

    /** Lucene full-text search. Returns matching md file IDs. */
    @GetMapping("/search")
    public List<String> search(@RequestParam String q,
                               @RequestParam(defaultValue = "20") int limit) throws IOException {
        return registry.getSearchService().search(q, limit);
    }

    /** MdFile detail: content, tags, headings, wikiLinks, backlinks. */
    @GetMapping("/notes/{id}")
    public ResponseEntity<Map<String, Object>> getMdFile(@PathVariable String id) {
        KnowledgeGraph graph = registry.getGraph();
        MdFileMetadata mdFile = graph.getMdFile(id);
        if (mdFile == null) {
            return ResponseEntity.notFound().build();
        }

        List<String> backlinks = graph.getNeighborEdges(id).stream()
                .map(e -> e.source().equals(id) ? e.target() : e.source())
                .distinct()
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",        mdFile.getId());
        body.put("title",     mdFile.getTitle());
        body.put("content",   mdFile.getContent());
        body.put("tags",      mdFile.getTags());
        body.put("headings",  mdFile.getHeadings());
        body.put("wikiLinks", mdFile.getWikiLinks());
        body.put("backlinks", backlinks);

        return ResponseEntity.ok(body);
    }

    /** Graph traversal from an md file up to a given depth. */
    @GetMapping("/traverse/{id}")
    public List<String> traverse(
            @PathVariable String id,
            @RequestParam(defaultValue = "2") int depth,
            @RequestParam(defaultValue = "5") int maxNeighbors,
            @RequestParam(defaultValue = "3") int minWeight) {
        return traversalService.traverse(registry.getGraph(), id, depth, maxNeighbors, minWeight);
    }

    /**
     * SSE endpoint — browser clients subscribe here to receive real-time
     * {@code graph-updated} events whenever markdown files change on disk.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToUpdates() {
        return registry.subscribe();
    }
}
