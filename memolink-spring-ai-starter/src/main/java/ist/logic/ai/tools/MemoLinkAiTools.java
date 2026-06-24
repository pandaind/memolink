package ist.logic.ai.tools;

import ist.logic.core.model.GraphContextResult;
import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.NoteDetail;
import ist.logic.core.model.SearchResult;
import ist.logic.core.service.CrossEncoderService;
import ist.logic.core.service.EmbeddingService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphSearchService.ChunkSearchResult;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.MdFileParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Spring AI tools exposing the MemoLink knowledge graph to an LLM agent.
 *
 * <p>This class mirrors the 16 tools of the MCP server ({@code MemoLinkMcpTools})
 * but is designed for direct programmatic use inside a Spring AI application —
 * no sidecar processes (headroom compression, stop-word filtering) are required.
 * Where the MCP server compresses output, these tools return the full raw text.
 *
 * <p>The underlying {@link GraphHolder} is updated in-place by
 * {@link ist.logic.core.service.GraphWatchService} whenever markdown files
 * change on disk, so every tool call uses the latest graph without restart.
 *
 * <p>Register with a {@code ChatClient}:
 * <pre>
 *   chatClient.prompt().user(message).tools(memoLinkAiTools).call().content()
 * </pre>
 */
public class MemoLinkAiTools {

    private static final Logger log = LoggerFactory.getLogger(MemoLinkAiTools.class);

    private final GraphHolder           holder;
    private final GraphTraversalService traversalService;
    private final EmbeddingService      embeddingService;
    private final CrossEncoderService   reranker;  // null when disabled
    private final Path                  vaultDir;

    public MemoLinkAiTools(GraphHolder holder,
                           GraphTraversalService traversalService,
                           EmbeddingService embeddingService,
                           CrossEncoderService reranker,
                           Path vaultDir) {
        this.holder           = holder;
        this.traversalService = traversalService;
        this.embeddingService = embeddingService;
        this.reranker         = reranker;
        this.vaultDir         = vaultDir;
    }

    // ── Discovery & Search ────────────────────────────────────────────────────

    @Tool(description = """
            Search memories by keyword and semantic similarity (hybrid BM25 + KNN).
            Returns matching note IDs, titles, and relevance scores.
            Higher score = more relevant. Prefer top results; skip score < 0.4.
            Use this to find relevant memories before reading their content.
            """)
    public List<SearchResult> search_memories(String query) {
        try {
            return holder.getSearchService().hybridSearch(
                    query, embeddingService, 10, holder.getGraph()::getMdFile, reranker);
        } catch (IOException e) {
            log.warn("search_memories failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Tool(description = """
            Pure semantic (vector) search over memories.
            Returns matching note IDs and scores.
            Only available when the embedding model is loaded.
            """)
    public List<SearchResult> semantic_search(String query) {
        if (!embeddingService.isAvailable()) return List.of();
        float[] qEmb = embeddingService.embed(query);
        if (qEmb == null) return List.of();
        try {
            return holder.getSearchService().semanticSearch(qEmb, 10);
        } catch (IOException e) {
            log.warn("semantic_search failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Tool(description = """
            Ask a question and get back the most relevant paragraphs from the vault.
            Returns chunk-level results with a score per paragraph.
            Use score to decide which excerpts to trust (discard below 0.4).
            Best first tool to call for any factual query.
            """)
    public List<Map<String, Object>> ask_vault(String query) {
        if (query == null || query.isBlank()) return List.of();
        if (!embeddingService.isAvailable()) return List.of();
        float[] qEmb = embeddingService.embed(query);
        if (qEmb == null) return List.of();

        List<ChunkSearchResult> hits;
        try {
            hits = holder.getSearchService().searchChunks(
                    qEmb, 5, query, reranker,
                    hit -> {
                        MdFileMetadata m = holder.getGraph().getMdFile(hit.fileId());
                        if (m == null || m.getChunkTexts() == null) return "";
                        if (hit.chunkIndex() >= m.getChunkTexts().size()) return "";
                        return m.getChunkTexts().get(hit.chunkIndex());
                    });
        } catch (IOException e) {
            log.warn("ask_vault search failed: {}", e.getMessage());
            return List.of();
        }

        // Group by file so each file appears once, with its matching excerpts
        Map<String, List<ChunkSearchResult>> grouped = hits.stream()
                .collect(Collectors.groupingBy(ChunkSearchResult::fileId,
                        LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> results = new ArrayList<>(grouped.size());
        for (var entry : grouped.entrySet()) {
            MdFileMetadata m = holder.getGraph().getMdFile(entry.getKey());
            if (m == null || m.getChunkTexts() == null) continue;

            List<Map<String, Object>> excerpts = new ArrayList<>();
            for (ChunkSearchResult hit : entry.getValue()) {
                if (hit.chunkIndex() >= m.getChunkTexts().size()) continue;
                excerpts.add(Map.of(
                        "score", Math.round(hit.score() * 100.0) / 100.0,
                        "text",  m.getChunkTexts().get(hit.chunkIndex())
                ));
            }
            results.add(Map.of("fileId", entry.getKey(), "excerpts", excerpts));
        }
        return results;
    }

    @Tool(description = """
            Get a summary of the entire vault: total memories, top tags,
            most-connected memories, and highest-importance memories.
            Call this first when the user's request is broad or ambiguous.
            """)
    public Map<String, Object> vault_summary() {
        var graph    = holder.getGraph();
        var memories = graph.getAllMdFiles();

        Map<String, Long> tagCounts = new TreeMap<>();
        memories.forEach(m -> m.getTags().forEach(t -> tagCounts.merge(t, 1L, Long::sum)));

        List<Map<String, Object>> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("tag", e.getKey(), "count", e.getValue()))
                .toList();

        List<Map<String, Object>> mostConnected = memories.stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "connections", graph.getNeighborEdges(m.getId()).size()))
                .sorted(Comparator.<Map<String, Object>, Integer>comparing(
                        e -> (Integer) e.get("connections")).reversed())
                .limit(10)
                .toList();

        List<Map<String, Object>> importantMemories = memories.stream()
                .filter(m -> m.getImportance() > 0)
                .sorted(Comparator.comparingInt(MdFileMetadata::getImportance).reversed())
                .map(m -> Map.<String, Object>of("id", m.getId(), "importance", m.getImportance()))
                .toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalMemories",         memories.size());
        summary.put("semanticSearchEnabled", embeddingService.isAvailable());
        summary.put("topTags",               topTags);
        summary.put("mostConnectedMemories", mostConnected);
        summary.put("importantMemories",     importantMemories);
        return summary;
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    @Tool(description = """
            Read the full content of a memory by its file ID.
            Returns title, body, tags, headings, and wiki-links.
            Prefer ask_vault for specific questions; use this for full context.
            """)
    public NoteDetail read_memory(String file_id) {
        MdFileMetadata m = holder.getGraph().getMdFile(file_id);
        if (m == null) return null;
        m.recordAccess();
        return NoteDetail.from(m);
    }

    @Tool(description = """
            Read a specific heading section from a memory.
            Returns only the text under that heading, not the entire file.
            Use this to load one section at a time for token efficiency.
            """)
    public Map<String, Object> read_memory_section(String file_id, String heading) {
        MdFileMetadata m = holder.getGraph().getMdFile(file_id);
        if (m == null) return Map.of("error", "File not found: " + file_id);
        m.recordAccess();

        List<String> lines = m.getContent().lines().toList();
        StringBuilder section = new StringBuilder();
        boolean inSection    = false;
        int     headingLevel = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String current = trimmed.substring(level).trim();

                if (inSection) {
                    if (level <= headingLevel) break;  // End of section
                } else if (current.equalsIgnoreCase(heading.trim())) {
                    inSection    = true;
                    headingLevel = level;
                }
            }
            if (inSection) section.append(line).append("\n");
        }

        if (!inSection) return Map.of("error", "Heading '" + heading + "' not found.");
        return Map.of("fileId", file_id, "heading", heading, "text", section.toString().trim());
    }

    @Tool(description = """
            Read a memory with full GraphRAG context: its body plus summaries
            of its immediate (1-hop) neighbours in the knowledge graph.
            Use instead of read_memory when graph relationships matter.
            """)
    public GraphContextResult get_memory_context(String file_id) {
        MdFileMetadata m = holder.getGraph().getMdFile(file_id);
        if (m != null) m.recordAccess();
        return traversalService.buildContext(holder.getGraph(), file_id);
    }

    @Tool(description = """
            List all memory IDs currently in the vault (sorted).
            Useful for browsing the full vault before deciding what to read.
            """)
    public List<String> list_memories() {
        return holder.getGraph().getAllMdFiles().stream()
                .map(MdFileMetadata::getId)
                .sorted()
                .toList();
    }

    // ── Graph traversal ───────────────────────────────────────────────────────

    @Tool(description = """
            Get memories related to a given memory via graph traversal (depth 2, top 5).
            Returns a list of connected memory IDs ordered by proximity.
            Use to expand context beyond direct search hits.
            """)
    public List<String> get_related_memories(String file_id) {
        return traversalService.traverse(holder.getGraph(), file_id, 2, 5, 3);
    }

    @Tool(description = """
            Traverse the knowledge graph from a memory up to a given depth (max 3).
            Returns connected memory IDs ordered by proximity.
            """)
    public List<String> traverse_memories(String file_id, int depth) {
        return traversalService.traverse(holder.getGraph(), file_id, Math.min(depth, 3), 5, 2);
    }

    @Tool(description = """
            Find the shortest path of connected memories between two memory IDs.
            Returns the sequence of memory IDs on the path, or empty list if none.
            """)
    public List<String> find_path(String from_id, String to_id) {
        return traversalService.findPath(holder.getGraph(), from_id, to_id);
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    @Tool(description = """
            Create a new memory in the vault.
            file_id    : path like 'skills/my-topic.md' (auto-normalised to kebab-case).
            title      : clear, specific, searchable title.
            body       : main markdown content. Start with '> [TYPE] TL;DR' for best embedding.
            wiki_links : file IDs of related memories, e.g. ["spring-boot.md"]. Use [] if none.
            tags       : tag names WITHOUT the # prefix, e.g. ["java", "skill"]. Use [] if none.
            metadata   : extra frontmatter key→value pairs, e.g. {"type": "skill", "created": "2025-01-01"}.
            Returns a status map with the normalised file ID and any auto-discovered links.
            """)
    public Map<String, Object> create_memory(String file_id,
                                             String title,
                                             String body,
                                             List<String> wiki_links,
                                             List<String> tags,
                                             Map<String, String> metadata) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        if (Files.exists(target)) {
            return Map.of("error", "File already exists: " + normalizedId + ". Use update_memory to modify it.");
        }
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wiki_links);
            Files.createDirectories(target.getParent());
            Files.writeString(target, buildMarkdown(title, body, tags, allLinks, metadata),
                    StandardOpenOption.CREATE_NEW);
            String autoLinked = allLinks.stream()
                    .filter(l -> wiki_links == null || !wiki_links.contains(l))
                    .collect(Collectors.joining(", "));
            return Map.of(
                    "status",     "success",
                    "fileId",     normalizedId,
                    "autoLinked", autoLinked
            );
        } catch (IOException e) {
            return Map.of("error", "Failed to create memory: " + e.getMessage());
        }
    }

    @Tool(description = """
            Update an existing memory. Read it first with read_memory to preserve existing content.
            file_id    : memory to update, e.g. 'skills/spring-boot.md'.
            title      : new title.
            body       : new markdown content (replaces existing body).
            wiki_links : complete new list of wiki-link targets. Use [] to clear all.
            tags       : complete new list of tags (no # prefix). Use [] to clear all.
            metadata   : frontmatter key→value pairs. Null fields use auto-defaults (e.g. today's date).
            Returns a status map with the normalised file ID and any auto-discovered links.
            """)
    public Map<String, Object> update_memory(String file_id,
                                             String title,
                                             String body,
                                             List<String> wiki_links,
                                             List<String> tags,
                                             Map<String, String> metadata) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        if (!Files.exists(target)) {
            return Map.of("error", "File not found: " + normalizedId + ". Use create_memory to create it.");
        }
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wiki_links);
            Files.createDirectories(target.getParent());
            Files.writeString(target, buildMarkdown(title, body, tags, allLinks, metadata),
                    StandardOpenOption.TRUNCATE_EXISTING);
            String autoLinked = allLinks.stream()
                    .filter(l -> wiki_links == null || !wiki_links.contains(l))
                    .collect(Collectors.joining(", "));
            return Map.of(
                    "status",     "success",
                    "fileId",     normalizedId,
                    "autoLinked", autoLinked
            );
        } catch (IOException e) {
            return Map.of("error", "Failed to update memory: " + e.getMessage());
        }
    }

    @Tool(description = """
            Permanently delete a memory from the vault by its file ID.
            Returns a status map. The knowledge graph is rebuilt automatically.
            """)
    public Map<String, Object> delete_memory(String file_id) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        if (!Files.exists(target)) {
            return Map.of("error", "File not found: " + normalizedId);
        }
        try {
            Files.delete(target);
            return Map.of("status", "success", "fileId", normalizedId);
        } catch (IOException e) {
            return Map.of("error", "Failed to delete memory: " + e.getMessage());
        }
    }

    // ── Ranking & synthesis ───────────────────────────────────────────────────

    @Tool(description = """
            Set the importance of a memory (0–10) to boost its ranking in future searches.
            Higher importance memories surface first in hybrid search results.
            Use 9–10 only for foundational architectural decisions or hard-won fixes.
            """)
    public Map<String, Object> set_memory_importance(String file_id, int importance) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        MdFileMetadata m = holder.getGraph().getMdFile(normalizedId);
        if (m == null) return Map.of("error", "File not found: " + normalizedId);
        int clamped = Math.max(0, Math.min(10, importance));
        m.setImportance(clamped);
        // Persist into frontmatter
        Path target = vaultDir.resolve(normalizedId);
        try {
            String content = Files.readString(target);
            String updated = setFrontmatterField(content, "importance", String.valueOf(clamped));
            Files.writeString(target, updated, StandardOpenOption.TRUNCATE_EXISTING);
            return Map.of("status", "success", "fileId", normalizedId, "importance", clamped);
        } catch (IOException e) {
            return Map.of("error", "Importance set in memory but could not persist to file: " + e.getMessage());
        }
    }

    @Tool(description = """
            Gather compressed excerpts from memories related to a topic.
            Returns up to max_sources memories with title, score, and a short excerpt.
            Use before writing a reflection or summary memory.
            """)
    public Map<String, Object> gather_sources(String topic, int max_sources) {
        int limit = max_sources > 0 ? Math.min(max_sources, 10) : 5;
        List<SearchResult> hits;
        try {
            hits = holder.getSearchService().hybridSearch(
                    topic, embeddingService, limit, holder.getGraph()::getMdFile);
        } catch (IOException e) {
            return Map.of("error", "Search failed: " + e.getMessage());
        }
        if (hits.isEmpty()) return Map.of("topic", topic, "results", List.of());

        List<Map<String, Object>> sources = hits.stream().map(r -> {
            MdFileMetadata m = holder.getGraph().getMdFile(r.id());
            String excerpt = m == null ? ""
                    : m.getContent().substring(0, Math.min(m.getContent().length(), 600));
            return Map.<String, Object>of(
                    "id",      r.id(),
                    "title",   r.title(),
                    "score",   Math.round(r.score() * 100.0) / 100.0,
                    "excerpt", excerpt
            );
        }).toList();

        return Map.of(
                "topic",       topic,
                "instruction", "Use create_memory to write a reflection that synthesises these sources. Tag it with 'reflection' and link each source.",
                "results",     sources
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Searches the vault for existing memories related to the given title+body
     * and merges them with the explicitly provided wiki-links (deduplicating).
     * Results below score 0.3 are discarded.
     */
    private List<String> autoDiscoverLinks(String title, String body,
                                           String excludeId, List<String> explicit) {
        String query = ((title == null ? "" : title) + " " +
                        (body  == null ? "" : body.substring(0, Math.min(body.length(), 500)))).trim();
        List<String> discovered = List.of();
        if (!query.isBlank()) {
            try {
                discovered = holder.getSearchService()
                        .searchWithScores(query, 5)
                        .stream()
                        .filter(r -> r.score() >= 0.3f)
                        .map(SearchResult::id)
                        .filter(id -> !id.equals(excludeId))
                        .toList();
            } catch (IOException e) {
                log.debug("autoDiscoverLinks search failed (non-fatal): {}", e.getMessage());
            }
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (explicit != null) merged.addAll(explicit);
        merged.addAll(discovered);
        return new ArrayList<>(merged);
    }

    /**
     * Renders a canonical MemoLink markdown document:
     * <pre>
     * ---
     * type: skill
     * created: 2025-01-15
     * ---
     *
     * # Title
     *
     * #tag1 #tag2
     *
     * {body}
     *
     * ## Related
     * - [[link1]]
     * </pre>
     */
    private static String buildMarkdown(String title,
                                        String body,
                                        List<String> tags,
                                        List<String> wikiLinks,
                                        Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder();

        // YAML frontmatter
        Map<String, String> fm = buildFrontmatter(metadata);
        if (!fm.isEmpty()) {
            sb.append("---\n");
            fm.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
            sb.append("---\n\n");
        }

        // Title
        sb.append("# ").append(title == null ? "Untitled" : title.trim()).append("\n");

        // Tags
        if (tags != null && !tags.isEmpty()) {
            sb.append("\n");
            String tagLine = tags.stream()
                    .map(t -> "#" + t.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-"))
                    .collect(Collectors.joining(" "));
            sb.append(tagLine).append("\n");
        }

        // Body
        if (body != null && !body.isBlank()) {
            sb.append("\n").append(body.trim()).append("\n");
        }

        // Related section
        if (wikiLinks != null && !wikiLinks.isEmpty()) {
            sb.append("\n## Related\n\n");
            for (String link : wikiLinks) {
                String ref = link.endsWith(".md") ? link.substring(0, link.length() - 3) : link;
                sb.append("- [[").append(ref).append("]]\n");
            }
        }

        return sb.toString();
    }

    /** Builds frontmatter map, auto-filling well-known fields when not supplied. */
    private static Map<String, String> buildFrontmatter(Map<String, String> supplied) {
        Map<String, String> fm = new LinkedHashMap<>();
        if (supplied != null) {
            supplied.forEach((k, v) -> { if (v != null && !v.isBlank()) fm.put(k, v); });
        }
        // Auto-fill created date if not supplied
        fm.putIfAbsent("created", LocalDate.now().toString());
        return fm;
    }

    /**
     * Inserts or replaces a YAML frontmatter scalar field.
     * If the file has no frontmatter block, a new one is prepended.
     */
    private static String setFrontmatterField(String content, String field, String value) {
        String fieldLine = field + ": " + value;
        if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
            int end = content.indexOf("\n---", 3);
            if (end != -1) {
                String fm   = content.substring(0, end);
                String rest = content.substring(end);
                fm = fm.contains("\n" + field + ":")
                        ? fm.replaceAll("(?m)^" + field + ":.*$", fieldLine)
                        : fm + "\n" + fieldLine;
                return fm + rest;
            }
        }
        return "---\n" + fieldLine + "\n---\n" + content;
    }
}
