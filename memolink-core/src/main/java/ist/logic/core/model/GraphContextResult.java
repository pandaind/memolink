package ist.logic.core.model;

import java.util.List;

/**
 * Result of a graph-context expansion: the matched note plus its 1-hop
 * neighbours with relationship metadata (Capability 3 — GraphRAG-style context).
 */
public record GraphContextResult(
        String       id,
        String       title,
        List<String> tags,
        List<String> headings,
        List<String> wikiLinks,
        String       body,
        List<Neighbor> neighbors
) {
    /**
     * A single 1-hop neighbour, carrying the edge weight, type and reasons
     * so the model understands the nature of the relationship.
     */
    public record Neighbor(
            String      id,
            String      title,
            int         edgeWeight,
            String      relationType,
            List<String> reasons
    ) {}
}
