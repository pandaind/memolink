package ist.logic.ai.tools;

import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.model.NoteDetail;
import ist.logic.core.model.SearchResult;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.MdFileParserService;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI tools exposing the mdgraph knowledge graph to an LLM agent.
 *
 * The underlying {@link GraphHolder} is updated in-place by
 * {@link ist.logic.core.service.GraphWatchService} whenever markdown files
 * change on disk, so every tool call automatically uses the latest graph
 * without any agent restart.
 *
 * <p>Write tools ({@link #createMdFile} and {@link #updateMdFile}) save files
 * directly to the notes directory. The running {@code GraphWatchService} picks
 * up the change and rebuilds the graph automatically — no restart needed.
 *
 * Flow the agent can use:
 *   1. searchMdFiles(query)         → find relevant md file IDs
 *   2. traverseGraph(fileId, 2)     → expand to related md files
 *   3. getMdFileContent(fileId)      → read actual markdown
 *   4. createMdFile / updateMdFile  → persist new knowledge
 *
 * Register with a {@code ChatClient} via:
 * <pre>
 *   chatClient.prompt().user(message).tools(memoLinkAiTools).call().content()
 * </pre>
 */
public class MemoLinkAiTools {

    private final GraphHolder           holder;
    private final GraphTraversalService traversalService;
    private final Path                  notesDir;

    public MemoLinkAiTools(GraphHolder holder,
                          GraphTraversalService traversalService,
                          Path notesDir) {
        this.holder           = holder;
        this.traversalService = traversalService;
        this.notesDir         = notesDir;
    }

    @Tool(description = """
            Search md files in the knowledge graph using a text query.
            Returns ranked results with id, title, and relevance score.
            Higher score means more relevant. Prefer top results; skip score < 0.5.
            Use this first to find relevant md files before reading their content.
            """)
    public List<SearchResult> searchMdFiles(String query) {
        try {
            return holder.getSearchService().searchWithScores(query, 10);
        } catch (IOException e) {
            return List.of();
        }
    }

    @Tool(description = """
            Get md files related to a given md file ID via graph traversal (depth=2, top-5 neighbours).
            Useful for expanding context beyond direct search hits.
            Returns a list of md file IDs.
            """)
    public List<String> getRelatedMdFiles(String fileId) {
        return traversalService.traverse(holder.getGraph(), fileId, 2, 5, 3);
    }

    @Tool(description = """
            Get the structured content of an md file by its ID (e.g. "spring.md").
            Returns title, tags, headings, wiki links, and body prose — not raw markdown.
            Only load md files you actually need — prefer searchMdFiles + getRelatedMdFiles first.
            """)
    public NoteDetail getMdFileContent(String fileId) {
        MdFileMetadata mdFile = holder.getGraph().getMdFile(fileId);
        if (mdFile == null) return null;
        return NoteDetail.from(mdFile);
    }

    @Tool(description = """
            Traverse the knowledge graph from a starting md file up to a given depth (max 3).
            Returns a list of connected md file IDs ordered by proximity.
            """)
    public List<String> traverseGraph(String fileId, int depth) {
        return traversalService.traverse(holder.getGraph(), fileId, Math.min(depth, 3), 5, 2);
    }

    @Tool(description = """
            Create a new markdown file in the notes directory.
            fileId    : target filename, e.g. "my-note.md" (normalised to kebab-case automatically).
            title     : note title rendered as the top-level H1 heading.
            body      : main markdown content (paragraphs, code blocks, etc.).
            wikiLinks : file IDs of related notes to link, e.g. ["spring-boot.md", "kafka.md"].
                        Use empty list [] when there are no related notes.
            tags      : tag names WITHOUT the # prefix, e.g. ["java", "spring"].
                        Use empty list [] when there are no tags.
            Returns the normalised file ID on success, or an error message if the file already exists.
            Related existing notes are discovered automatically and added to the wiki-links.
            The knowledge graph is rebuilt automatically after the file is saved.
            """)
    public String createMdFile(String fileId,
                               String title,
                               String body,
                               List<String> wikiLinks,
                               List<String> tags) {
        String normalizedId = MdFileParserService.normalizeMdFileId(fileId);
        Path target = notesDir.resolve(normalizedId);
        if (Files.exists(target)) {
            return "File already exists: " + normalizedId + ". Use updateMdFile to modify it.";
        }
        try {
            List<String> allLinks = autoDiscoverLinks(title, body, normalizedId, wikiLinks);
            Files.createDirectories(notesDir);
            Files.writeString(target, buildMarkdown(title, body, allLinks, tags),
                    StandardOpenOption.CREATE_NEW);
            String autoLinked = allLinks.stream()
                    .filter(l -> wikiLinks == null || !wikiLinks.contains(l))
                    .collect(Collectors.joining(", "));
            return "Created: " + normalizedId +
                    (autoLinked.isBlank() ? "" : " (auto-linked: " + autoLinked + ")");
        } catch (IOException e) {
            return "Failed to create file: " + e.getMessage();
        }
    }

    @Tool(description = """
            Update an existing markdown file in the notes directory.
            Read the current content first with getMdFileContent if you want to preserve parts of it.
            fileId    : file to update, e.g. "spring-boot.md".
            title     : new H1 title for the file.
            body      : new main markdown content.
            wikiLinks : complete new list of wiki-link targets, e.g. ["kafka.md"].
                        Use empty list [] to remove all links.
            tags      : complete new list of tags (no # prefix), e.g. ["java"].
                        Use empty list [] to remove all tags.
            Returns the file ID on success, or an error message if the file does not exist.
            The knowledge graph is rebuilt automatically after the file is saved.
            """)
    public String updateMdFile(String fileId,
                               String title,
                               String body,
                               List<String> wikiLinks,
                               List<String> tags) {
        String normalizedId = MdFileParserService.normalizeMdFileId(fileId);
        Path target = notesDir.resolve(normalizedId);
        if (!Files.exists(target)) {
            return "File not found: " + normalizedId + ". Use createMdFile to create it.";
        }
        try {
            Files.writeString(target, buildMarkdown(title, body, wikiLinks, tags),
                    StandardOpenOption.TRUNCATE_EXISTING);
            return "Updated: " + normalizedId;
        } catch (IOException e) {
            return "Failed to update file: " + e.getMessage();
        }
    }

    @Tool(description = """
            List all md file IDs currently in the knowledge graph.
            Returns every file ID, e.g. ["spring-boot.md", "kafka.md"].
            Useful for browsing the full vault before deciding what to read or edit.
            """)
    public List<String> listMdFiles() {
        return holder.getGraph().getAllMdFiles().stream()
                .map(MdFileMetadata::getId)
                .sorted()
                .toList();
    }

    @Tool(description = """
            Delete an existing md file from the notes directory.
            fileId : the file to delete, e.g. "old-note.md".
            Returns the file ID on success, or an error message if the file does not exist.
            The knowledge graph is rebuilt automatically after deletion.
            """)
    public String deleteMdFile(String fileId) {
        String normalizedId = MdFileParserService.normalizeMdFileId(fileId);
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

    /**
     * Renders a canonical MemoLink markdown document:
     * <pre>
     * # Title
     *
     * #tag1 #tag2
     *
     * {body}
     *
     * ## Related
     * - [[link1]]
     * - [[link2]]
     * </pre>
     * Tags are embedded inline (picked up by {@code MdFileParserService}).
     * Wiki links are listed in a trailing "Related" section.
     * Keywords are derived automatically from the body text on next parse.
     */
    private String buildMarkdown(String title,
                                 String body,
                                 List<String> wikiLinks,
                                 List<String> tags) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(title == null ? "Untitled" : title.trim()).append("\n");

        if (tags != null && !tags.isEmpty()) {
            sb.append("\n");
            String tagLine = tags.stream()
                    .map(t -> "#" + t.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-"))
                    .collect(Collectors.joining(" "));
            sb.append(tagLine).append("\n");
        }

        if (body != null && !body.isBlank()) {
            sb.append("\n").append(body.trim()).append("\n");
        }

        if (wikiLinks != null && !wikiLinks.isEmpty()) {
            sb.append("\n## Related\n\n");
            for (String link : wikiLinks) {
                // Strip .md suffix for the display ref inside [[ ]]
                String ref = link.endsWith(".md")
                        ? link.substring(0, link.length() - 3)
                        : link;
                sb.append("- [[").append(ref).append("]]\n");
            }
        }

        return sb.toString();
    }
}
