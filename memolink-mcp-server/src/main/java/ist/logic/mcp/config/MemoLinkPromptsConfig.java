package ist.logic.mcp.config;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import ist.logic.mcp.template.PromptTemplateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Registers MCP prompt specifications backed by Mustache templates in
 * {@code classpath:prompts/}.  Edit the {@code .mustache} files to change
 * prompt wording without recompiling.  Configured metadata fields are
 * automatically injected into every template via {@link PromptTemplateService}.
 */
@Configuration
public class MemoLinkPromptsConfig {

    private final PromptTemplateService promptTemplateService;

    public MemoLinkPromptsConfig(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> memoLinkPrompts() {
        return List.of(
                listNotesPrompt(),
                searchNotesPrompt(),
                createNotePrompt(),
                updateNotePrompt(),
                deleteNotePrompt()
        );
    }

    // ── list_notes ────────────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification listNotesPrompt() {
        var prompt = new McpSchema.Prompt(
                "list_notes",
                "List all notes in the knowledge graph and summarise their topics",
                List.of()
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) ->
                new McpSchema.GetPromptResult(
                        "Browse all notes in the knowledge graph",
                        List.of(new McpSchema.PromptMessage(
                                McpSchema.Role.USER,
                                new McpSchema.TextContent(render("list_notes", Map.of()))
                        ))
                )
        );
    }

    // ── search_notes ──────────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification searchNotesPrompt() {
        var prompt = new McpSchema.Prompt(
                "search_notes",
                "Search notes by topic or keyword and show related notes",
                List.of(new McpSchema.PromptArgument("query",
                        "Topic or keywords to search for", true))
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            String query = arg(req.arguments(), "query");
            return new McpSchema.GetPromptResult(
                    "Search notes for: " + query,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("search_notes", Map.of("query", query)))
                    ))
            );
        });
    }

    // ── create_note ───────────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification createNotePrompt() {
        var prompt = new McpSchema.Prompt(
                "create_note",
                "Create a new markdown note in the knowledge graph",
                List.of(
                        new McpSchema.PromptArgument("title",
                                "Title of the new note", true),
                        new McpSchema.PromptArgument("body",
                                "Main content for the note (markdown)", false),
                        new McpSchema.PromptArgument("tags",
                                "Comma-separated tags, no # prefix (e.g. java,spring)", false),
                        new McpSchema.PromptArgument("links",
                                "Comma-separated file IDs to link (e.g. spring-boot.md,kafka.md)", false)
                )
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            var args    = req.arguments();
            String title = arg(args, "title");
            String body  = arg(args, "body");
            String tags  = arg(args, "tags");
            String links = arg(args, "links");
            var ctx = Map.<String, Object>of(
                    "title",       title,
                    "body",        body,
                    "hasBody",     !body.isBlank(),
                    "tags",        tags,
                    "hasTags",     !tags.isBlank(),
                    "links",       links,
                    "hasLinks",    !links.isBlank()
            );
            return new McpSchema.GetPromptResult(
                    "Create note: " + title,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("create_note", ctx))
                    ))
            );
        });
    }

    // ── update_note ───────────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification updateNotePrompt() {
        var prompt = new McpSchema.Prompt(
                "update_note",
                "Read and update an existing note in the knowledge graph",
                List.of(
                        new McpSchema.PromptArgument("file_id",
                                "File ID to update, e.g. spring-boot.md", true),
                        new McpSchema.PromptArgument("instructions",
                                "What to change or add (freeform)", false)
                )
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            var args         = req.arguments();
            String fileId    = arg(args, "file_id");
            String instr     = arg(args, "instructions");
            var ctx = Map.<String, Object>of(
                    "fileId",          fileId,
                    "instructions",    instr,
                    "hasInstructions", !instr.isBlank()
            );
            return new McpSchema.GetPromptResult(
                    "Update note: " + fileId,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("update_note", ctx))
                    ))
            );
        });
    }

    // ── delete_note ───────────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification deleteNotePrompt() {
        var prompt = new McpSchema.Prompt(
                "delete_note",
                "Delete a note from the knowledge graph after checking for dependents",
                List.of(new McpSchema.PromptArgument("file_id",
                        "File ID to delete, e.g. old-note.md", true))
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            String fileId = arg(req.arguments(), "file_id");
            return new McpSchema.GetPromptResult(
                    "Delete note: " + fileId,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("delete_note", Map.of("fileId", fileId)))
                    ))
            );
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String render(String templateName, Map<String, Object> vars) {
        try {
            return promptTemplateService.render(templateName, vars);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render prompt template: " + templateName, e);
        }
    }

    private static String arg(Map<String, Object> args, String key) {
        if (args == null) return "";
        Object v = args.get(key);
        return v == null ? "" : v.toString().trim();
    }
}

