package ist.logic.mcp.tools;

import ist.logic.core.model.GraphContextResult;
import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.NoteDetail;
import ist.logic.core.model.SearchResult;
import ist.logic.core.service.EmbeddingService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.MdFileParserService;
import ist.logic.mcp.service.HeadroomCompressionService;
import ist.logic.mcp.service.StopWordFilterService;
import ist.logic.mcp.template.NoteTemplateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tools exposed via Spring AI MCP server.
 *
 * The underlying {@link GraphHolder} is updated in-place by
 * {@link ist.logic.core.service.GraphWatchService} whenever markdown files
 * change on disk, so Claude sees up-to-date content without server restarts.
 *
 * Claude Desktop config (~/Library/Application Support/Claude/claude_desktop_config.json):
 * <pre>
 * {
 *   "mcpServers": {
 *     "memolink": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/memolink-mcp-server.jar"],
 *       "env": { "MDGRAPH_NOTES_DIR": "/path/to/your/notes" }
 *     }
 *   }
 * }
 * </pre>
 */
@Service
public class MemoLinkMcpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphHolder               holder;
    private final GraphTraversalService     traversalService;
    private final Path                      vaultDir;
    private final NoteTemplateService       noteTemplateService;
    private final EmbeddingService          embeddingService;
    private final HeadroomCompressionService headroomService;
    private final StopWordFilterService     stopWordFilterService;

    public MemoLinkMcpTools(GraphHolder holder,
                           GraphTraversalService traversalService,
                           Path mdGraphVaultDir,
                           NoteTemplateService noteTemplateService,
                           EmbeddingService embeddingService,
                           HeadroomCompressionService headroomService,
                           StopWordFilterService stopWordFilterService) {
        this.holder               = holder;
        this.traversalService     = traversalService;
        this.vaultDir             = mdGraphVaultDir;
        this.noteTemplateService  = noteTemplateService;
        this.embeddingService     = embeddingService;
        this.headroomService      = headroomService;
        this.stopWordFilterService = stopWordFilterService;
    }

    @Tool(description = "Search md files via semantic search. Returns matching file IDs, titles, and excerpts.")
    public String search_md_files(String query) {
        try {
            return formatSearchResults(holder.getSearchService().hybridSearch(
                    query, embeddingService, 10, holder.getGraph()::getMdFile));
        } catch (IOException e) {
            return toJson(Map.of("error", "Error searching files: " + e.getMessage()));
        }
    }

    @Tool(description = "Get related md file IDs via graph traversal up to depth 2.")
    public String get_related_md_files(String file_id) {
        return formatStringList(traversalService.traverse(holder.getGraph(), file_id, 2, 5, 3));
    }

    @Tool(description = "Get full markdown content, tags, and links for a file by its ID.")
    public String get_md_file(String file_id) {
        MdFileMetadata mdFile = holder.getGraph().getMdFile(file_id);
        if (mdFile == null) return toJson(Map.of("error", "File not found."));
        mdFile.recordAccess();   // Capability 5: metadata ranking
        NoteDetail detail = NoteDetail.from(mdFile);
        
        // Strip stop words first
        String strippedBody = stopWordFilterService.strip(detail.body());
        
        // Compress the body via headroom sidecar; falls back to original on error
        String compressedBody = headroomService.compress(strippedBody);
        
        if (compressedBody != detail.body()) {
            detail = new NoteDetail(
                    detail.id(), detail.title(), detail.tags(),
                    detail.headings(), detail.wikiLinks(), compressedBody);
        }
        return formatNoteDetail(detail);
    }

    @Tool(description = "Traverse the graph from a file up to given depth (max 3). Returns connected file IDs.")
    public String traverse_graph(String file_id, int depth) {
        return formatStringList(traversalService.traverse(holder.getGraph(), file_id, Math.min(depth, 3), 5, 2));
    }

    @Tool(description = "List all file IDs in the vault.")
    public String list_md_files() {
        return formatStringList(holder.getGraph().getAllMdFiles().stream()
                .map(MdFileMetadata::getId)
                .sorted()
                .toList());
    }


    @Tool(description = "Get a specific heading section from an md file.")
    public String get_md_file_section(String file_id, String heading) {
        MdFileMetadata mdFile = holder.getGraph().getMdFile(file_id);
        if (mdFile == null) return toJson(Map.of("error", "File not found."));
        mdFile.recordAccess();
        String content = mdFile.getContent();
        
        List<String> lines = content.lines().toList();
        StringBuilder section = new StringBuilder();
        boolean inSection = false;
        int headingLevel = 0;
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                String currentHeading = trimmed.substring(level).trim();
                
                if (inSection) {
                    if (level <= headingLevel) {
                        break; // End of section
                    }
                } else if (currentHeading.equalsIgnoreCase(heading.trim())) {
                    inSection = true;
                    headingLevel = level;
                    section.append(line).append("\n");
                    continue;
                }
            }
            if (inSection) {
                section.append(line).append("\n");
            }
        }
        
        if (!inSection) {
            return toJson(Map.of("error", "Heading '" + heading + "' not found in file."));
        }
        
        String extracted = section.toString().trim();
        String stripped = stopWordFilterService.strip(extracted);
        String compressed = headroomService.compress(stripped);
        return toJson(Map.of(
            "fileId", file_id,
            "heading", heading,
            "text", compressed
        ));
    }

    @Tool(description = "Pure semantic vector search. Returns matching file IDs and excerpts.")
    public String semantic_search(String query) {
        if (!embeddingService.isAvailable()) return toJson(Map.of("error", "Semantic search is disabled (model not available)."));
        float[] qEmb = embeddingService.embed(query);
        if (qEmb == null) return toJson(Map.of("error", "Failed to generate embedding for query."));
        try {
            return formatSearchResults(holder.getSearchService().semanticSearch(qEmb, 10));
        } catch (IOException e) {
            return toJson(Map.of("error", "Error during semantic search: " + e.getMessage()));
        }
    }

    @Tool(description = "Get a note's full content plus its 1-hop graph neighbors (GraphRAG context).")
    public String get_graph_context(String file_id) {
        MdFileMetadata m = holder.getGraph().getMdFile(file_id);
        if (m != null) m.recordAccess();
        GraphContextResult ctx = traversalService.buildContext(holder.getGraph(), file_id);
        if (ctx == null) return toJson(Map.of("error", "File not found in graph."));
        // Compress the focal note's body; neighbour summaries are already short
        String compressedBody = headroomService.compress(ctx.body());
        if (compressedBody != ctx.body()) {
            ctx = new GraphContextResult(
                    ctx.id(), ctx.title(), ctx.tags(),
                    ctx.headings(), ctx.wikiLinks(), compressedBody,
                    ctx.neighbors());
        }
        return formatGraphContext(ctx);
    }

    @Tool(description = "Find shortest path of connected notes between from_id and to_id.")
    public String find_path_between_notes(String from_id, String to_id) {
        List<String> path = traversalService.findPath(holder.getGraph(), from_id, to_id);
        if (path.isEmpty()) return toJson(Map.of("error", "No path found between " + from_id + " and " + to_id + "."));
        return formatStringList(path);
    }

    @Tool(description = "Create a new md file. Args: file_id, title, body, wiki_links, tags, metadata.")
    @PreAuthorize("hasRole('WRITE')")
    public String create_md_file(String file_id,
                                 String title,
                                 String body,
                                 List<String> wiki_links,
                                 List<String> tags,
                                 Map<String, String> metadata) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        if (Files.exists(target)) {
            return toJson(Map.of("error", "File already exists: " + normalizedId + ". Use update_md_file to modify it."));
        }
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wiki_links);
            Files.createDirectories(target.getParent());
            Files.writeString(target, noteTemplateService.render(title, body, tags, allLinks, metadata),
                    StandardOpenOption.CREATE_NEW);
            String autoLinked = allLinks.stream()
                    .filter(l -> wiki_links == null || !wiki_links.contains(l))
                    .collect(Collectors.joining(", "));
            return toJson(Map.of(
                "status", "success",
                "message", "Created: " + normalizedId,
                "autoLinked", autoLinked
            ));
        } catch (IOException e) {
            return toJson(Map.of("error", "Failed to create file: " + e.getMessage()));
        }
    }

    @Tool(description = "Update an existing md file. Args: file_id, title, body, wiki_links, tags, metadata.")
    @PreAuthorize("hasRole('WRITE')")
    public String update_md_file(String file_id,
                                 String title,
                                 String body,
                                 List<String> wiki_links,
                                 List<String> tags,
                                 Map<String, String> metadata) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wiki_links);
            Files.createDirectories(target.getParent());
            Files.writeString(target, noteTemplateService.render(title, body, tags, allLinks, metadata),
                    StandardOpenOption.TRUNCATE_EXISTING);
            String autoLinked = allLinks.stream()
                    .filter(l -> wiki_links == null || !wiki_links.contains(l))
                    .collect(Collectors.joining(", "));
            return toJson(Map.of(
                "status", "success",
                "message", "Updated: " + normalizedId,
                "autoLinked", autoLinked
            ));
        } catch (IOException e) {
            return toJson(Map.of("error", "Failed to update file: " + e.getMessage()));
        }
    }

    @Tool(description = "Delete an md file by file_id.")
    @PreAuthorize("hasRole('WRITE')")
    public String delete_md_file(String file_id) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        if (!Files.exists(target)) {
            return toJson(Map.of("error", "File not found: " + normalizedId));
        }
        try {
            Files.delete(target);
            return toJson(Map.of("status", "success", "message", "Deleted: " + normalizedId));
        } catch (IOException e) {
            return toJson(Map.of("error", "Failed to delete file: " + e.getMessage()));
        }
    }

    @Tool(description = "Set note importance (0-10) to boost its search ranking.")
    @PreAuthorize("hasRole('WRITE')")
    public String set_note_importance(String file_id, int importance) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        MdFileMetadata m = holder.getGraph().getMdFile(normalizedId);
        if (m == null) return toJson(Map.of("error", "File not found: " + normalizedId));
        int clamped = Math.max(0, Math.min(10, importance));
        m.setImportance(clamped);
        // Persist into frontmatter by re-reading + updating the file
        Path target = vaultDir.resolve(normalizedId);
        try {
            String content = Files.readString(target);
            String updated = setFrontmatterField(content, "importance", String.valueOf(clamped));
            Files.writeString(target, updated, StandardOpenOption.TRUNCATE_EXISTING);
            return toJson(Map.of("status", "success", "message", "Importance set to " + clamped + " for: " + normalizedId));
        } catch (IOException e) {
            return toJson(Map.of("error", "Importance updated in memory but could not persist: " + e.getMessage()));
        }
    }

    @Tool(description = "Returns summary of the vault: total notes, top tags, and highly connected notes. Returns JSON.")
    public String get_memory_summary() {
        var graph = holder.getGraph();
        var allNotes = graph.getAllMdFiles();

        Map<String, Long> tagCounts = new java.util.TreeMap<>();
        allNotes.forEach(n -> n.getTags().forEach(t -> tagCounts.merge(t, 1L, Long::sum)));
        List<Map<String, Object>> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("tag", e.getKey(), "count", e.getValue()))
                .toList();

        List<Map<String, Object>> mostConnected = allNotes.stream()
                .map(n -> Map.<String, Object>of("id", n.getId(), "connections", graph.getNeighborEdges(n.getId()).size()))
                .sorted(Comparator.<Map<String, Object>, Integer>comparing(m -> (Integer) m.get("connections")).reversed())
                .limit(10)
                .toList();

        List<Map<String, Object>> byImportance = allNotes.stream()
                .filter(n -> n.getImportance() > 0)
                .sorted(Comparator.comparingInt(MdFileMetadata::getImportance).reversed())
                .map(n -> Map.<String, Object>of("id", n.getId(), "importance", n.getImportance()))
                .toList();

        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("totalNotes", allNotes.size());
        summary.put("semanticSearchEnabled", embeddingService.isAvailable());
        summary.put("topTags", topTags);
        summary.put("mostConnectedNotes", mostConnected);
        summary.put("importantNotes", byImportance);

        return toJson(summary);
    }

    @Tool(description = "Gather excerpts from notes related to a topic to help write a reflection summary. Returns structured JSON.")
    public String gather_reflection_sources(String topic, int max_sources) {
        int limit = max_sources > 0 ? Math.min(max_sources, 10) : 5;
        List<SearchResult> hits;
        try {
            hits = holder.getSearchService().hybridSearch(
                    topic, embeddingService, limit, holder.getGraph()::getMdFile);
        } catch (IOException e) {
            return toJson(Map.of("error", "Search failed: " + e.getMessage()));
        }

        if (hits.isEmpty()) {
            return toJson(Map.of("results", List.of()));
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        for (var r : hits) {
            MdFileMetadata m = holder.getGraph().getMdFile(r.id());
            String raw = m == null ? "" :
                    m.getContent().substring(0, Math.min(m.getContent().length(), 600));
            String excerpt = headroomService.compress(raw);
            
            sources.add(Map.of(
                "id", r.id(),
                "title", r.title(),
                "score", Math.round(r.score() * 100.0) / 100.0,
                "excerpt", excerpt
            ));
        }
        
        return toJson(Map.of(
            "topic", topic,
            "instruction", "Use create_md_file to write a reflection note that synthesises these sources. Tag it with #reflection and link to each source.",
            "results", sources
        ));
    }

    @Tool(description = "Search the entire vault and return the exact relevant paragraphs to answer the query. Returns structured JSON data, grouped by file to avoid duplicate graph connections.")
    public String query_vault(String query) {
        if (query == null || query.isBlank()) return "{\"error\": \"Query is empty.\"}";

        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null) return "{\"error\": \"Embedding service unavailable.\"}";

        List<ist.logic.core.service.GraphSearchService.ChunkSearchResult> hits;
        try {
            hits = holder.getSearchService().searchChunks(queryVector, 5);
        } catch (IOException e) {
            return "{\"error\": \"Search failed: " + e.getMessage() + "\"}";
        }

        if (hits.isEmpty()) return "{\"results\": []}";

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("query", query);
        List<Map<String, Object>> resultsList = new ArrayList<>();

        // Group hits by fileId to avoid repeating graph connections for the same file
        Map<String, List<ist.logic.core.service.GraphSearchService.ChunkSearchResult>> groupedHits = hits.stream()
                .collect(Collectors.groupingBy(ist.logic.core.service.GraphSearchService.ChunkSearchResult::fileId,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<ist.logic.core.service.GraphSearchService.ChunkSearchResult>> entry : groupedHits.entrySet()) {
            String fileId = entry.getKey();
            List<ist.logic.core.service.GraphSearchService.ChunkSearchResult> fileHits = entry.getValue();

            MdFileMetadata m = holder.getGraph().getMdFile(fileId);
            if (m == null || m.getChunkTexts() == null) continue;

            Map<String, Object> fileResult = new java.util.LinkedHashMap<>();
            fileResult.put("fileId", fileId);

            List<Map<String, Object>> excerpts = new ArrayList<>();
            java.util.Set<Integer> seenIndices = new java.util.HashSet<>();

            for (ist.logic.core.service.GraphSearchService.ChunkSearchResult hit : fileHits) {
                if (hit.chunkIndex() >= m.getChunkTexts().size()) continue;
                if (!seenIndices.add(hit.chunkIndex())) continue; // deduplicate chunks from same file

                String text = m.getChunkTexts().get(hit.chunkIndex());
                String compressed = headroomService.compress(stopWordFilterService.strip(text));
                
                Map<String, Object> chunkObj = new java.util.LinkedHashMap<>();
                chunkObj.put("score", Math.round(hit.score() * 100.0) / 100.0);
                chunkObj.put("text", compressed);
                excerpts.add(chunkObj);
            }
            fileResult.put("excerpts", excerpts);
            resultsList.add(fileResult);
        }
        
        response.put("results", resultsList);

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize JSON\"}";
        }
    }

    // ── Formatting helpers for token efficiency ──────────────────────────────

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\": \"Serialization failed\"}";
        }
    }

    private String formatSearchResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return "{\"results\": []}";
        List<Map<String, Object>> mapped = results.stream()
            .map(r -> Map.<String, Object>of(
                "id", r.id(), 
                "title", r.title(), 
                "score", Math.round(r.score() * 100.0) / 100.0))
            .toList();
        return toJson(Map.of("results", mapped));
    }

    private String formatStringList(List<String> list) {
        if (list == null) list = List.of();
        return toJson(Map.of("items", list));
    }

    private String formatNoteDetail(NoteDetail detail) {
        if (detail == null) return "{\"error\": \"File not found.\"}";
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", detail.id());
        map.put("title", detail.title());
        map.put("tags", detail.tags() == null ? List.of() : detail.tags());
        map.put("links", detail.wikiLinks() == null ? List.of() : detail.wikiLinks());
        map.put("body", detail.body());
        return toJson(map);
    }

    private String formatGraphContext(GraphContextResult ctx) {
        if (ctx == null) return "{\"error\": \"File not found.\"}";
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", ctx.id());
        map.put("title", ctx.title());
        map.put("tags", ctx.tags() == null ? List.of() : ctx.tags());
        map.put("body", ctx.body());
        
        List<Map<String, Object>> neighbors = new ArrayList<>();
        if (ctx.neighbors() != null) {
            for (var n : ctx.neighbors()) {
                neighbors.add(Map.of(
                    "id", n.id(),
                    "title", n.title(),
                    "type", n.relationType(),
                    "weight", n.edgeWeight()
                ));
            }
        }
        map.put("neighbors", neighbors);
        return toJson(map);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Searches existing notes for ones related to the given title+body and merges
     * them with the explicitly provided wiki-links, deduplicating.
     * Results with Lucene score below 0.3 are discarded.
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
            } catch (IOException ignored) {}
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (explicit != null) merged.addAll(explicit);
        merged.addAll(discovered);
        return new ArrayList<>(merged);
    }

    /**
     * Inserts or replaces a YAML frontmatter scalar field.
     * If the file has no frontmatter, a new frontmatter block is prepended.
     */
    private static String setFrontmatterField(String content, String field, String value) {
        String fieldLine = field + ": " + value;
        // Has frontmatter?
        if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
            int end = content.indexOf("\n---", 3);
            if (end != -1) {
                String fm = content.substring(0, end);
                String rest = content.substring(end);
                if (fm.contains("\n" + field + ":")) {
                    fm = fm.replaceAll("(?m)^" + field + ":.*$", fieldLine);
                } else {
                    fm = fm + "\n" + fieldLine;
                }
                return fm + rest;
            }
        }
        // No frontmatter — prepend one
        return "---\n" + fieldLine + "\n---\n" + content;
    }

}

