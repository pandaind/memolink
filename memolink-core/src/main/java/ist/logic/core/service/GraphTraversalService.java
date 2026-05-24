package ist.logic.core.service;

import ist.logic.core.model.GraphEdge;
import ist.logic.core.model.KnowledgeGraph;

import java.util.*;

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
}
