package ist.logic.core.model;

import java.util.Set;

/**
 * A weighted, undirected edge between two notes.
 *
 * weight  = sum of signal scores:
 *   wiki_link      → +5
 *   shared_tags    → +2 per shared tag (max 3)
 *   shared_keywords → +1 per shared keyword (max 5)
 */
public record GraphEdge(String source, String target, int weight, Set<String> reasons) {}
