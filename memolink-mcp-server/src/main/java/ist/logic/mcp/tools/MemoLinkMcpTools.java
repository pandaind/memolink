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
            return "Error searching files: " + e.getMessage();
        }
    }

    @Tool(description = "Get related md file IDs via graph traversal up to depth 2.")
    public String get_related_md_files(String file_id) {
        return formatStringList(traversalService.traverse(holder.getGraph(), file_id, 2, 5, 3));
    }

    @Tool(description = "Get full markdown content, tags, and links for a file by its ID.")
    public String get_md_file(String file_id) {
        MdFileMetadata mdFile = holder.getGraph().getMdFile(file_id);
        if (mdFile == null) return "File not found.";
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
        if (mdFile == null) return "File not found.";
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
            return "Heading '" + heading + "' not found in file.";
        }
        
        String extracted = section.toString().trim();
        String stripped = stopWordFilterService.strip(extracted);
        return headroomService.compress(stripped);
    }

    @Tool(description = "Pure semantic vector search. Returns matching file IDs and excerpts.")
    public String semantic_search(String query) {
        if (!embeddingService.isAvailable()) return "Semantic search is disabled (model not available).";
        float[] qEmb = embeddingService.embed(query);
        if (qEmb == null) return "Failed to generate embedding for query.";
        try {
            return formatSearchResults(holder.getSearchService().semanticSearch(qEmb, 10));
        } catch (IOException e) {
            return "Error during semantic search: " + e.getMessage();
        }
    }

    @Tool(description = "Get a note's full content plus its 1-hop graph neighbors (GraphRAG context).")
    public String get_graph_context(String file_id) {
        MdFileMetadata m = holder.getGraph().getMdFile(file_id);
        if (m != null) m.recordAccess();
        GraphContextResult ctx = traversalService.buildContext(holder.getGraph(), file_id);
        if (ctx == null) return "File not found in graph.";
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
        if (path.isEmpty()) return "No path found between " + from_id + " and " + to_id + ".";
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
            return "File already exists: " + normalizedId + ". Use update_md_file to modify it.";
        }
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wiki_links);
            Files.createDirectories(target.getParent());
            Files.writeString(target, noteTemplateService.render(title, body, tags, allLinks, metadata),
                    StandardOpenOption.CREATE_NEW);
            String autoLinked = allLinks.stream()
                    .filter(l -> wiki_links == null || !wiki_links.contains(l))
                    .collect(Collectors.joining(", "));
            return "Created: " + normalizedId +
                    (autoLinked.isBlank() ? "" : " (auto-linked: " + autoLinked + ")");
        } catch (IOException e) {
            return "Failed to create file: " + e.getMessage();
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
            return "Updated: " + normalizedId +
                    (autoLinked.isBlank() ? "" : " (auto-linked: " + autoLinked + ")");
        } catch (IOException e) {
            return "Failed to update file: " + e.getMessage();
        }
    }

    @Tool(description = "Delete an md file by file_id.")
    @PreAuthorize("hasRole('WRITE')")
    public String delete_md_file(String file_id) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = vaultDir.resolve(normalizedId);
        if (!Files.exists(target)) {
            return "File not found: " + normalizedId;
        }
        try {
            Files.delete(target);
            return "Deleted: " + normalizedId;
        } catch (IOException e) {
            return "Failed to delete file: " + e.getMessage();
        }
    }

    @Tool(description = "Set note importance (0-10) to boost its search ranking.")
    @PreAuthorize("hasRole('WRITE')")
    public String set_note_importance(String file_id, int importance) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        MdFileMetadata m = holder.getGraph().getMdFile(normalizedId);
        if (m == null) return "File not found: " + normalizedId;
        int clamped = Math.max(0, Math.min(10, importance));
        m.setImportance(clamped);
        // Persist into frontmatter by re-reading + updating the file
        Path target = vaultDir.resolve(normalizedId);
        try {
            String content = Files.readString(target);
            String updated = setFrontmatterField(content, "importance", String.valueOf(clamped));
            Files.writeString(target, updated, StandardOpenOption.TRUNCATE_EXISTING);
            return "Importance set to " + clamped + " for: " + normalizedId;
        } catch (IOException e) {
            return "Importance updated in memory but could not persist: " + e.getMessage();
        }
    }

    @Tool(description = "Returns summary of the vault: total notes, top tags, and highly connected notes.")
    public String get_memory_summary() {
        var graph = holder.getGraph();
        var allNotes = graph.getAllMdFiles();

        // Top tags
        Map<String, Long> tagCounts = new java.util.TreeMap<>();
        allNotes.forEach(n -> n.getTags().forEach(t ->
                tagCounts.merge(t, 1L, Long::sum)));
        List<Map<String, Object>> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("tag", e.getKey(), "count", e.getValue()))
                .toList();

        // Most connected notes
        List<Map<String, Object>> mostConnected = allNotes.stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "connections", graph.getNeighborEdges(n.getId()).size()))
                .sorted(Comparator.<Map<String, Object>, Integer>comparing(
                        m -> (Integer) m.get("connections")).reversed())
                .limit(10)
                .toList();

        // Notes by importance
        List<Map<String, Object>> byImportance = allNotes.stream()
                .filter(n -> n.getImportance() > 0)
                .sorted(Comparator.comparingInt(MdFileMetadata::getImportance).reversed())
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(), "importance", n.getImportance()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("# Memory Summary\n\n");
        sb.append("- **Total Notes:** ").append(allNotes.size()).append("\n");
        sb.append("- **Semantic Search Enabled:** ").append(embeddingService.isAvailable()).append("\n\n");
        
        sb.append("## Top Tags\n");
        if (topTags.isEmpty()) sb.append("None\n");
        for (var tag : topTags) {
            sb.append("- ").append(tag.get("tag")).append(" (").append(tag.get("count")).append(")\n");
        }
        
        sb.append("\n## Most Connected Notes\n");
        if (mostConnected.isEmpty()) sb.append("None\n");
        for (var n : mostConnected) {
            sb.append("- **").append(n.get("id")).append("** (").append(n.get("connections")).append(" connections)\n");
        }
        
        sb.append("\n## Important Notes\n");
        if (byImportance.isEmpty()) sb.append("None\n");
        for (var n : byImportance) {
            sb.append("- **").append(n.get("id")).append("** (Importance: ").append(n.get("importance")).append(")\n");
        }
        return sb.toString();
    }

    @Tool(description = "Gather excerpts from notes related to a topic to help write a reflection summary.")
    public String gather_reflection_sources(String topic, int max_sources) {
        int limit = max_sources > 0 ? Math.min(max_sources, 10) : 5;
        List<SearchResult> hits;
        try {
            hits = holder.getSearchService().hybridSearch(
                    topic, embeddingService, limit, holder.getGraph()::getMdFile);
        } catch (IOException e) {
            hits = List.of();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Reflection Sources for: ").append(topic).append("\n\n");
        if (hits.isEmpty()) {
            sb.append("No sources found for this topic.\n");
            return sb.toString();
        }

        for (var r : hits) {
            MdFileMetadata m = holder.getGraph().getMdFile(r.id());
            String raw = m == null ? "" :
                    m.getContent().substring(0, Math.min(m.getContent().length(), 600));
            String excerpt = headroomService.compress(raw);
            
            sb.append("## ").append(r.title()).append(" (").append(r.id()).append(")\n");
            sb.append("**Score:** ").append(r.score()).append("\n\n");
            sb.append(excerpt).append("\n\n");
        }
        sb.append("---\n**Instruction:** Use create_md_file to write a reflection note that synthesises these sources. Tag it with #reflection and link to each source.");
        return sb.toString();
    }

    @Tool(description = "Search the entire vault and return the exact relevant paragraphs to answer the query.")
    public String query_vault(String query) {
        if (query == null || query.isBlank()) return "Query is empty.";
        
        List<SearchResult> hits;
        try {
            hits = holder.getSearchService().hybridSearch(
                    query, embeddingService, 3, holder.getGraph()::getMdFile);
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }

        if (hits.isEmpty()) return "No relevant notes found for your query.";

        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null) return "Embedding service unavailable.";

        // A simple record to hold chunk scores
        record ChunkScore(String fileId, String content, float score) {}
        List<ChunkScore> allChunks = new java.util.ArrayList<>();

        for (SearchResult hit : hits) {
            MdFileMetadata m = holder.getGraph().getMdFile(hit.id());
            if (m == null || m.getContent() == null) continue;

            // Split into paragraphs roughly
            String[] paragraphs = m.getContent().split("\\n\\s*\\n");
            for (String p : paragraphs) {
                String cleanP = p.trim();
                if (cleanP.length() < 50) continue; // skip tiny lines

                float[] pVector = embeddingService.embed(cleanP);
                if (pVector != null) {
                    float score = cosineSimilarity(queryVector, pVector);
                    allChunks.add(new ChunkScore(hit.id(), cleanP, score));
                }
            }
        }

        // Sort descending
        allChunks.sort((a, b) -> Float.compare(b.score(), a.score()));

        // Take top 5
        int limit = Math.min(5, allChunks.size());
        StringBuilder sb = new StringBuilder();
        sb.append("# Top Results for: ").append(query).append("\n\n");
        for (int i = 0; i < limit; i++) {
            ChunkScore c = allChunks.get(i);
            String compressed = headroomService.compress(stopWordFilterService.strip(c.content()));
            sb.append("### From [").append(c.fileId()).append("] (Score: ")
              .append(String.format("%.2f", c.score())).append(")\n");
            sb.append(compressed).append("\n\n");
        }

        return sb.toString();
    }

    private float cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) return 0f;
        float dot = 0f;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
        }
        return dot;
    }

    // ── Formatting helpers for token efficiency ──────────────────────────────

    private String formatSearchResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return "No results found.";
        return results.stream()
                .map(r -> String.format("- **%s** (%s) - Score: %.2f", r.id(), r.title(), r.score()))
                .collect(Collectors.joining("\n"));
    }

    private String formatStringList(List<String> list) {
        if (list == null || list.isEmpty()) return "None";
        return list.stream().map(s -> "- " + s).collect(Collectors.joining("\n"));
    }

    private String formatNoteDetail(NoteDetail detail) {
        if (detail == null) return "File not found.";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(detail.title()).append("\n\n");
        if (detail.tags() != null && !detail.tags().isEmpty()) {
            sb.append("**Tags:** ").append(String.join(", ", detail.tags())).append("\n");
        }
        if (detail.wikiLinks() != null && !detail.wikiLinks().isEmpty()) {
            sb.append("**Links:** ").append(String.join(", ", detail.wikiLinks())).append("\n");
        }
        sb.append("\n").append(detail.body());
        return sb.toString();
    }

    private String formatGraphContext(GraphContextResult ctx) {
        if (ctx == null) return "File not found.";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ctx.title()).append("\n\n");
        if (ctx.tags() != null && !ctx.tags().isEmpty()) {
            sb.append("**Tags:** ").append(String.join(", ", ctx.tags())).append("\n");
        }
        sb.append("\n").append(ctx.body()).append("\n\n");
        sb.append("## Neighbors\n");
        if (ctx.neighbors() == null || ctx.neighbors().isEmpty()) {
            sb.append("No neighbors found.\n");
        } else {
            for (var n : ctx.neighbors()) {
                sb.append(String.format("- **%s** (%s) [Type: %s, Weight: %d]\n", 
                        n.id(), n.title(), n.relationType(), n.edgeWeight()));
            }
        }
        return sb.toString();
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

