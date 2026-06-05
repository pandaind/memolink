package ist.logic.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.*;

/**
 * The in-memory knowledge graph.
 *
 * Serialises to {@code {"nodes":[...], "edges":[...]}} via Jackson.
 * All graph-lookup methods are {@code @JsonIgnore} so they don't appear in the JSON.
 */
public class KnowledgeGraph {

    private final List<GraphNode> nodes;
    private final List<GraphEdge> edges;

    @JsonIgnore
    private final Map<String, MdFileMetadata> fileIndex;

    /** Adjacency: file-id → list of edges (both directions stored). */
    @JsonIgnore
    private final Map<String, List<GraphEdge>> adjacency;

    public KnowledgeGraph(List<MdFileMetadata> files, List<GraphEdge> edges) {
        this.fileIndex  = new LinkedHashMap<>();
        this.nodes      = new ArrayList<>();

        for (MdFileMetadata file : files) {
            fileIndex.put(file.getId(), file);
            nodes.add(new GraphNode(file.getId(), file.getTitle()));
        }

        this.edges = new ArrayList<>(edges);

        // Build bidirectional adjacency for traversal
        this.adjacency = new HashMap<>();
        for (GraphEdge edge : edges) {
            adjacency.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(
                new GraphEdge(edge.target(), edge.source(), edge.weight(), edge.reasons(), edge.relationType()));
        }
    }

    // ── JSON-serialisable accessors ──────────────────────────────────────────

    public List<GraphNode> getNodes() { return Collections.unmodifiableList(nodes); }
    public List<GraphEdge> getEdges() { return Collections.unmodifiableList(edges); }

    // ── Graph-lookup helpers (not serialised) ────────────────────────────────

    @JsonIgnore
    public MdFileMetadata getMdFile(String id) {
        return fileIndex.get(id);
    }

    @JsonIgnore
    public List<GraphEdge> getNeighborEdges(String fileId) {
        return adjacency.getOrDefault(fileId, Collections.emptyList());
    }

    @JsonIgnore
    public Collection<MdFileMetadata> getAllMdFiles() {
        return fileIndex.values();
    }

    @JsonIgnore
    public int size() { return nodes.size(); }
}
