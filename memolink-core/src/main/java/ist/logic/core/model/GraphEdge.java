package ist.logic.core.model;

import java.util.Set;

/**
 * A weighted edge between two notes, with an optional typed relationship.
 *
 * weight  = sum of signal scores:
 *   wiki_link       → +5
 *   shared_tags     → +2 per shared tag (max 3)
 *   shared_keywords → +1 per shared keyword (max 5)
 *
 * relationType is inferred from the wiki-link anchor syntax:
 *   [[target]]         → "references"  (default)
 *   [[target|uses]]    → "uses"
 *   [[target|integrates_with]] → "integrates_with"
 */
public record GraphEdge(
        String      source,
        String      target,
        int         weight,
        Set<String> reasons,
        String      relationType
) {
    /** Backwards-compatible factory — defaults relationType to "references". */
    public static GraphEdge of(String source, String target, int weight, Set<String> reasons) {
        return new GraphEdge(source, target, weight, reasons, "references");
    }
}
