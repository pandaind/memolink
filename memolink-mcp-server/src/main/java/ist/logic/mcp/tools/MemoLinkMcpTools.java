package ist.logic.mcp.tools;

import ist.logic.core.model.GraphContextResult;
import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.NoteDetail;
import ist.logic.core.model.SearchResult;
import ist.logic.core.service.EmbeddingService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.MdFileParserService;
import ist.logic.mcp.template.NoteTemplateService;
import org.springframework.ai.tool.annotation.Tool;
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

    private final GraphHolder           holder;
    private final GraphTraversalService traversalService;
    private final Path                  notesDir;
    private final NoteTemplateService   noteTemplateService;
    private final EmbeddingService      embeddingService;

    public MemoLinkMcpTools(GraphHolder holder,
                           GraphTraversalService traversalService,
                           Path mdGraphNotesDir,
                           NoteTemplateService noteTemplateService,
                           EmbeddingService embeddingService) {
        this.holder               = holder;
        this.traversalService     = traversalService;
        this.notesDir             = mdGraphNotesDir;
        this.noteTemplateService  = noteTemplateService;
        this.embeddingService     = embeddingService;
    }

    @Tool(description = """
            Search md files by query text using hybrid BM25 + semantic search.
            Returns ranked results with id, title, and relevance score.
            Higher score means more relevant. Prefer top results; skip score < 0.5.
            When the embedding model is available, results match conceptually related
            content even without exact keyword overlap.
            """)
    public List<SearchResult> search_md_files(String query) {
        try {
            return holder.getSearchService().hybridSearch(
                    query, embeddingService, 10, holder.getGraph()::getMdFile);
        } catch (IOException e) {
            return List.of();
        }
    }

    @Tool(description = """
            Get md files related to a given md file via knowledge graph traversal.
            Returns a list of related md file IDs (up to depth=2, top-5 per level).
            """)
    public List<String> get_related_md_files(String file_id) {
        return traversalService.traverse(holder.getGraph(), file_id, 2, 5, 3);
    }

    @Tool(description = """
            Get the structured content of an md file by its ID (e.g. "spring.md").
            Returns title, tags, headings, wiki links, and body prose — not raw markdown.
            """)
    public NoteDetail get_md_file(String file_id) {
        MdFileMetadata mdFile = holder.getGraph().getMdFile(file_id);
        if (mdFile == null) return null;
        mdFile.recordAccess();   // Capability 5: metadata ranking
        return NoteDetail.from(mdFile);
    }

    @Tool(description = """
            Traverse the knowledge graph from a starting md file.
            depth: 1–3 (recommended: 2).
            Returns connected md file IDs ordered by proximity.
            """)
    public List<String> traverse_graph(String file_id, int depth) {
        return traversalService.traverse(holder.getGraph(), file_id, Math.min(depth, 3), 5, 2);
    }

    @Tool(description = """
            List all md file IDs currently in the knowledge graph.
            Returns every file ID, e.g. ["spring-boot.md", "kafka.md"].
            Useful for browsing the full vault before deciding what to read or edit.
            """)
    public List<String> list_md_files() {
        return holder.getGraph().getAllMdFiles().stream()
                .map(MdFileMetadata::getId)
                .sorted()
                .toList();
    }

    @Tool(description = """
            Pure semantic (vector) search using sentence embeddings.
            Use this when keyword search returns poor results or when you need
            conceptually related content without exact keyword matches.
            Requires the embedding model to be available; returns empty list otherwise.
            Returns ranked results with id, title, and cosine similarity score.
            """)
    public List<SearchResult> semantic_search(String query) {
        if (!embeddingService.isAvailable()) return List.of();
        float[] qEmb = embeddingService.embed(query);
        if (qEmb == null) return List.of();
        try {
            return holder.getSearchService().semanticSearch(qEmb, 10);
        } catch (IOException e) {
            return List.of();
        }
    }

    @Tool(description = """
            Get a note's full content together with its 1-hop graph neighbours
            and the typed relationship to each (e.g. uses, integrates_with, references).
            This gives GraphRAG-style context: the matched note PLUS its immediate
            knowledge neighbourhood in one call.
            file_id : the note to expand, e.g. "spring-ai.md".
            """)
    public GraphContextResult get_graph_context(String file_id) {
        MdFileMetadata m = holder.getGraph().getMdFile(file_id);
        if (m != null) m.recordAccess();
        return traversalService.buildContext(holder.getGraph(), file_id);
    }

    @Tool(description = """
            Find the shortest path in the knowledge graph between two notes.
            Returns the ordered list of note IDs on the path, inclusive of
            from_id and to_id. Returns an empty list if no path exists.
            Useful for understanding how two topics are connected.
            from_id : starting note, e.g. "spring-ai.md".
            to_id   : target note, e.g. "kafka.md".
            """)
    public List<String> find_path_between_notes(String from_id, String to_id) {
        return traversalService.findPath(holder.getGraph(), from_id, to_id);
    }

    @Tool(description = """
            Create a new markdown file in the notes directory.
            file_id   : target filename, e.g. "my-note.md" (normalised to kebab-case automatically).
            title     : note title rendered as the top-level H1 heading.
            body      : main markdown content.
            wiki_links: file IDs of related notes to link, e.g. ["spring-boot.md"].
            tags      : tag names WITHOUT the # prefix, e.g. ["java", "spring"].
            metadata  : optional key-value pairs written into the note frontmatter,
                        e.g. {"source": "https://..."}. Configured fields are auto-discovered;
                        "created" / "date" fields are auto-set to today if present in config.
            Returns the normalised file ID on success, or an error if the file already exists.
            Related existing notes are discovered automatically and added to the wiki-links.
            The knowledge graph is rebuilt automatically after the file is saved.
            """)
    public String create_md_file(String file_id,
                                 String title,
                                 String body,
                                 List<String> wiki_links,
                                 List<String> tags,
                                 Map<String, String> metadata) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = notesDir.resolve(normalizedId);
        if (Files.exists(target)) {
            return "File already exists: " + normalizedId + ". Use update_md_file to modify it.";
        }
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wiki_links);
            Files.createDirectories(notesDir);
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

    @Tool(description = """
            Update an existing markdown file in the notes directory.
            Read current content first with get_md_file if you want to preserve parts of it.
            file_id   : file to update, e.g. "spring-boot.md".
            title     : new H1 title.
            body      : new main markdown content.
            wiki_links: complete new list of wiki-link targets.
            tags      : complete new list of tags (no # prefix).
            metadata  : key-value pairs for the note frontmatter. Pass existing values
                        from the current note to preserve them; omit a key to drop it.
            Returns the file ID on success, or an error if the file does not exist.
            The knowledge graph is rebuilt automatically after the file is saved.
            """)
    public String update_md_file(String file_id,
                                 String title,
                                 String body,
                                 List<String> wiki_links,
                                 List<String> tags,
                                 Map<String, String> metadata) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = notesDir.resolve(normalizedId);
        if (!Files.exists(target)) {
            return "File not found: " + normalizedId + ". Use create_md_file to create it.";
        }
        try {
            Files.writeString(target, noteTemplateService.render(title, body, tags, wiki_links, metadata),
                    StandardOpenOption.TRUNCATE_EXISTING);
            return "Updated: " + normalizedId;
        } catch (IOException e) {
            return "Failed to update file: " + e.getMessage();
        }
    }

    @Tool(description = """
            Delete an existing md file from the notes directory.
            file_id : the file to delete, e.g. "old-note.md".
            Returns the file ID on success, or an error if the file does not exist.
            The knowledge graph is rebuilt automatically after deletion.
            """)
    public String delete_md_file(String file_id) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        Path target = notesDir.resolve(normalizedId);
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

    @Tool(description = """
            Set the importance of a note (0–10). Higher importance boosts the note
            in search rankings and signals it should be prioritised by agents.
            The value is written into the note's frontmatter as "importance: N"
            so it persists across server restarts.
            file_id    : the note to update, e.g. "spring-ai.md".
            importance : integer 0–10 (0 = unset, 10 = most important).
            """)
    public String set_note_importance(String file_id, int importance) {
        String normalizedId = MdFileParserService.normalizeMdFileId(file_id);
        MdFileMetadata m = holder.getGraph().getMdFile(normalizedId);
        if (m == null) return "File not found: " + normalizedId;
        int clamped = Math.max(0, Math.min(10, importance));
        m.setImportance(clamped);
        // Persist into frontmatter by re-reading + updating the file
        Path target = notesDir.resolve(normalizedId);
        try {
            String content = Files.readString(target);
            String updated = setFrontmatterField(content, "importance", String.valueOf(clamped));
            Files.writeString(target, updated, StandardOpenOption.TRUNCATE_EXISTING);
            return "Importance set to " + clamped + " for: " + normalizedId;
        } catch (IOException e) {
            return "Importance updated in memory but could not persist: " + e.getMessage();
        }
    }

    @Tool(description = """
            Returns a summary of the knowledge graph: total note count, top tags,
            most-connected notes, notes by importance, and embedding availability.
            Use this to orient yourself before searching or editing.
            """)
    public Map<String, Object> get_memory_summary() {
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
                        "title", n.getTitle(),
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
                        "id", n.getId(), "title", n.getTitle(), "importance", n.getImportance()))
                .toList();

        return Map.of(
                "totalNotes",       allNotes.size(),
                "topTags",          topTags,
                "mostConnected",    mostConnected,
                "byImportance",     byImportance,
                "semanticEnabled",  embeddingService.isAvailable()
        );
    }

    @Tool(description = """
            Gather all notes related to a topic to prepare a reflection/summary node.
            Returns the topic, list of related note IDs, and their key content excerpts
            so the model can synthesise a summary note using create_md_file.
            topic       : the topic to summarise, e.g. "Spring AI".
            max_sources : maximum number of source notes to include (default 5).
            """)
    public Map<String, Object> gather_reflection_sources(String topic, int max_sources) {
        int limit = max_sources > 0 ? Math.min(max_sources, 10) : 5;
        List<SearchResult> hits;
        try {
            hits = holder.getSearchService().hybridSearch(
                    topic, embeddingService, limit, holder.getGraph()::getMdFile);
        } catch (IOException e) {
            hits = List.of();
        }

        List<Map<String, Object>> sources = hits.stream()
                .map(r -> {
                    MdFileMetadata m = holder.getGraph().getMdFile(r.id());
                    String excerpt = m == null ? "" :
                            m.getContent().substring(0, Math.min(m.getContent().length(), 600));
                    return Map.<String, Object>of(
                            "id",      r.id(),
                            "title",   r.title(),
                            "score",   r.score(),
                            "excerpt", excerpt
                    );
                })
                .toList();

        return Map.of(
                "topic",           topic,
                "sources",         sources,
                "instruction",     "Use create_md_file to write a reflection note that synthesises " +
                                   "these sources. Tag it with #reflection and link to each source."
        );
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

