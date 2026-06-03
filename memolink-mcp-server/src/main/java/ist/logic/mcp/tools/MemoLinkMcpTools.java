package ist.logic.mcp.tools;

import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.NoteDetail;
import ist.logic.core.model.SearchResult;
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

    public MemoLinkMcpTools(GraphHolder holder,
                           GraphTraversalService traversalService,
                           Path mdGraphNotesDir,
                           NoteTemplateService noteTemplateService) {
        this.holder               = holder;
        this.traversalService     = traversalService;
        this.notesDir             = mdGraphNotesDir;
        this.noteTemplateService  = noteTemplateService;
    }

    @Tool(description = """
            Search md files by query text.
            Returns ranked results with id, title, and relevance score.
            Higher score means more relevant. Prefer top results; skip score < 0.5.
            """)
    public List<SearchResult> search_md_files(String query) {
        try {
            return holder.getSearchService().searchWithScores(query, 10);
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

}

