package ist.logic.core.service;

import ist.logic.core.model.GraphEdge;
import ist.logic.core.model.MdFileMetadata;

import java.util.*;

/**
 * Computes weighted edges between notes based on three signals:
 *
 *   wiki_link       +5   (if A links to B or B links to A)
 *   shared_tags     +2   per shared tag, capped at 3 tags  → max +6
 *   shared_keywords +1   per shared keyword, capped at 5   → max +5
 *
 * <p><b>Algorithm</b>: builds inverted indexes (tag → note IDs,
 * keyword → note IDs, wikiLink → note ID) to avoid the O(n²) pair scan.
 * Only pairs that share at least one signal are ever scored, reducing
 * comparisons from O(n²) to O(n·k) where k is the average fan-out per token.
 *
 * Only pairs with score > 0 produce an edge.
 */
public class RelationshipEngine {

    public static final int WIKI_LINK_WEIGHT      = 5;
    public static final int SHARED_TAG_WEIGHT     = 2;
    public static final int SHARED_KEYWORD_WEIGHT = 1;

    public List<GraphEdge> buildEdges(List<MdFileMetadata> notes) {
        // Index notes by ID for quick lookup
        Map<String, MdFileMetadata> byId = new LinkedHashMap<>(notes.size() * 2);
        for (MdFileMetadata n : notes) byId.put(n.getId(), n);

        // Inverted indexes
        Map<String, List<String>> tagIndex     = new HashMap<>();
        Map<String, List<String>> keywordIndex = new HashMap<>();

        for (MdFileMetadata n : notes) {
            for (String tag : n.getTags()) {
                tagIndex.computeIfAbsent(tag, k -> new ArrayList<>()).add(n.getId());
            }
            for (String kw : n.getKeywords()) {
                keywordIndex.computeIfAbsent(kw, k -> new ArrayList<>()).add(n.getId());
            }
        }

        // Accumulate scores per canonical (sourceId < targetId) pair
        Map<String, int[]> pairScore        = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> pairReasonCount = new LinkedHashMap<>();
        // Explicit relationship types extracted from [[target|type]] wiki-link syntax
        Map<String, String> wikiLinkTypes   = new LinkedHashMap<>();

        // ── Wiki links (with optional type from [[target|type]] syntax) ─────────────────
        for (MdFileMetadata n : notes) {
            for (String linkedId : n.getWikiLinks()) {
                if (!byId.containsKey(linkedId)) continue;
                String key = pairKey(n.getId(), linkedId);
                pairScore.computeIfAbsent(key, k -> new int[1])[0] += WIKI_LINK_WEIGHT;
                pairReasonCount.computeIfAbsent(key, k -> new LinkedHashMap<>()).put("wiki_link", 1);
                // Extract explicit relationship type from wiki-link anchor (stored separately on MdFileMetadata)
                String explicit = n.getWikiLinkType(linkedId);
                if (explicit != null && !explicit.isBlank()) {
                    wikiLinkTypes.putIfAbsent(key, explicit);
                }
            }
        }

        // ── Shared tags ─────────────────────────────────────────────────────
        for (List<String> bucket : tagIndex.values()) {
            accumulatePairs(bucket, SHARED_TAG_WEIGHT, 3, "shared_tags", pairScore, pairReasonCount);
        }

        // ── Shared keywords ─────────────────────────────────────────────────
        for (List<String> bucket : keywordIndex.values()) {
            accumulatePairs(bucket, SHARED_KEYWORD_WEIGHT, 5, "shared_keywords", pairScore, pairReasonCount);
        }

        // ── Assemble edges ─────────────────────────────────────────
        List<GraphEdge> edges = new ArrayList<>(pairScore.size());
        for (Map.Entry<String, int[]> entry : pairScore.entrySet()) {
            if (entry.getValue()[0] <= 0) continue;
            String[] parts = entry.getKey().split("\t", 2);
            Map<String, Integer> reasonCount = pairReasonCount.getOrDefault(entry.getKey(), Map.of());
            Set<String> reasons = reasonCount.isEmpty() ? Set.of() : Set.copyOf(reasonCount.keySet());
            // Derive a relationType: prefer explicit type from wiki-link anchor, else infer
            String relType = wikiLinkTypes.getOrDefault(entry.getKey(), inferRelType(reasons));
            edges.add(new GraphEdge(parts[0], parts[1], entry.getValue()[0], reasons, relType));
        }
        return edges;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * For every pair in {@code bucket}, add {@code weight} to the pair score
     * (capped at {@code maxContributions} total additions per bucket).
     */
    private void accumulatePairs(List<String> bucket,
                                 int weight,
                                 int maxContributions,
                                 String reason,
                                 Map<String, int[]> pairScore,
                                 Map<String, Map<String, Integer>> pairReasonCount) {
        int n = bucket.size();
        if (n < 2) return;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                String key = pairKey(bucket.get(i), bucket.get(j));
                int[] score = pairScore.computeIfAbsent(key, k -> new int[1]);
                Map<String, Integer> reasonCount = pairReasonCount.computeIfAbsent(key, k -> new LinkedHashMap<>());
                int existing = reasonCount.getOrDefault(reason, 0);
                if (existing < maxContributions) {
                    score[0] += weight;
                    reasonCount.put(reason, existing + 1);
                }
            }
        }
    }

    /** Canonical pair key: lexicographically smaller ID first, tab-separated. */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + '\t' + b : b + '\t' + a;
    }

    /** Infer a generic relationship type from the edge signal reasons. */
    private static String inferRelType(Set<String> reasons) {
        if (reasons.contains("wiki_link"))      return "references";
        if (reasons.contains("shared_tags"))    return "related_topic";
        if (reasons.contains("shared_keywords")) return "similar_content";
        return "related";
    }
}
