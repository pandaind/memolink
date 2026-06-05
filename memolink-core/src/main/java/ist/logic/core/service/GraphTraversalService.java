package ist.logic.core.service;

import ist.logic.core.model.GraphEdge;
import ist.logic.core.model.KnowledgeGraph;

import java.util.*;
import java.util.Comparator;

/**
 * BFS traversal of the knowledge graph starting from a given note.
 *
 * Recommended defaults (from info.md):
 *   maxDepth     = 1–2
 *   maxNeighbors = 5
 *   minWeight    = 3
 */
public class GraphTraversalService {

    /**
     * @param graph         the knowledge graph to traverse
     * @param startNoteId   starting note ID (e.g. "spring.md")
     * @param maxDepth      maximum BFS depth
     * @param maxNeighbors  maximum neighbours to expand per node (highest weight first)
     * @param minWeight     edges below this weight are ignored
     * @return ordered list of discovered note IDs (startNoteId excluded)
     */
    public List<String> traverse(KnowledgeGraph graph,
                                  String startNoteId,
                                  int maxDepth,
                                  int maxNeighbors,
                                  int minWeight) {
        List<String> result  = new ArrayList<>();
        Set<String> visited  = new HashSet<>();
        visited.add(startNoteId);

        Deque<String> queue  = new ArrayDeque<>();
        Map<String, Integer> depthMap = new HashMap<>();
        queue.add(startNoteId);
        depthMap.put(startNoteId, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depthMap.get(current);

            if (depth >= maxDepth) { continue; }

            graph.getNeighborEdges(current).stream()
                    .filter(e -> e.weight() >= minWeight)
                    .sorted(Comparator.comparingInt(GraphEdge::weight).reversed())
                    .limit(maxNeighbors)
                    .forEach(edge -> {
                        String neighbor = edge.target(); // adjacency is already expanded bidirectionally
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            result.add(neighbor);
                            queue.add(neighbor);
                            depthMap.put(neighbor, depth + 1);
                        }
                    });
        }

        return result;
    }

    /**
     * BFS path-finding between two notes (Capability 8 / 10).
     *
     * @return ordered list of node IDs forming the shortest path from
     *         {@code fromId} to {@code toId}, inclusive; empty if no path exists.
     */
    public List<String> findPath(KnowledgeGraph graph, String fromId, String toId) {
        if (fromId.equals(toId)) return List.of(fromId);
        if (graph.getMdFile(fromId) == null || graph.getMdFile(toId) == null) return List.of();

        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        parent.put(fromId, null);
        queue.add(fromId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (GraphEdge edge : graph.getNeighborEdges(current)) {
                String neighbor = edge.target();
                if (!parent.containsKey(neighbor)) {
                    parent.put(neighbor, current);
                    if (neighbor.equals(toId)) {
                        return reconstructPath(parent, fromId, toId);
                    }
                    queue.add(neighbor);
                }
            }
        }
        return List.of(); // no path
    }

    /**
     * Builds a {@link ist.logic.core.model.GraphContextResult} for a note:
     * the note's detail plus all 1-hop neighbours with edge metadata.
     */
    public ist.logic.core.model.GraphContextResult buildContext(
            KnowledgeGraph graph, String noteId) {
        ist.logic.core.model.MdFileMetadata m = graph.getMdFile(noteId);
        if (m == null) return null;

        List<ist.logic.core.model.GraphContextResult.Neighbor> neighbors =
                graph.getNeighborEdges(noteId).stream()
                        .sorted(Comparator.comparingInt(GraphEdge::weight).reversed())
                        .map(e -> {
                            ist.logic.core.model.MdFileMetadata nb = graph.getMdFile(e.target());
                            String nbTitle = nb != null ? nb.getTitle() : e.target();
                            return new ist.logic.core.model.GraphContextResult.Neighbor(
                                    e.target(), nbTitle, e.weight(), e.relationType(),
                                    new java.util.ArrayList<>(e.reasons()));
                        })
                        .toList();

        String body = ist.logic.core.model.NoteDetail.from(m).body();
        return new ist.logic.core.model.GraphContextResult(
                m.getId(), m.getTitle(),
                List.copyOf(m.getTags()),
                List.copyOf(m.getHeadings()),
                List.copyOf(m.getWikiLinks()),
                body,
                neighbors
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static List<String> reconstructPath(Map<String, String> parent,
                                                 String from, String to) {
        Deque<String> path = new ArrayDeque<>();
        String cur = to;
        while (cur != null) {
            path.addFirst(cur);
            cur = parent.get(cur);
        }
        return new ArrayList<>(path);
    }
}
