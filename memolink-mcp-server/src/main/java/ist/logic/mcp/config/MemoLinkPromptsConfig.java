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
                semanticSearchPrompt(),
                createNotePrompt(),
                updateNotePrompt(),
                deleteNotePrompt(),
                findPathPrompt(),
                generateReflectionPrompt(),
                memoryOverviewPrompt()
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

    // ── semantic_search ───────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification semanticSearchPrompt() {
        var prompt = new McpSchema.Prompt(
                "semantic_search",
                "Search notes by concept using vector embeddings — finds related content without exact keyword matches",
                List.of(new McpSchema.PromptArgument("query",
                        "Concept or natural language description to search for", true))
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            String query = arg(req.arguments(), "query");
            return new McpSchema.GetPromptResult(
                    "Semantic search: " + query,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("semantic_search", Map.of("query", query)))
                    ))
            );
        });
    }

    // ── find_path ─────────────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification findPathPrompt() {
        var prompt = new McpSchema.Prompt(
                "find_path",
                "Find the connection path between two notes in the knowledge graph",
                List.of(
                        new McpSchema.PromptArgument("from_id",
                                "Starting note file ID, e.g. spring-ai.md", true),
                        new McpSchema.PromptArgument("to_id",
                                "Target note file ID, e.g. kafka.md", true)
                )
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            var args = req.arguments();
            String fromId = arg(args, "from_id");
            String toId   = arg(args, "to_id");
            var ctx = Map.<String, Object>of("fromId", fromId, "toId", toId);
            return new McpSchema.GetPromptResult(
                    "Find path: " + fromId + " → " + toId,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("find_path", ctx))
                    ))
            );
        });
    }

    // ── generate_reflection ───────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification generateReflectionPrompt() {
        var prompt = new McpSchema.Prompt(
                "generate_reflection",
                "Gather notes on a topic and synthesise a reflection/summary node",
                List.of(
                        new McpSchema.PromptArgument("topic",
                                "Topic to summarise, e.g. \"Spring AI\"", true),
                        new McpSchema.PromptArgument("max_sources",
                                "Max source notes to include (default 5)", false)
                )
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) -> {
            var args = req.arguments();
            String topic      = arg(args, "topic");
            String maxSources = arg(args, "max_sources");
            var ctx = new java.util.HashMap<String, Object>();
            ctx.put("topic", topic);
            if (!maxSources.isBlank()) ctx.put("maxSources", maxSources);
            return new McpSchema.GetPromptResult(
                    "Generate reflection: " + topic,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(render("generate_reflection", ctx))
                    ))
            );
        });
    }

    // ── memory_overview ───────────────────────────────────────────────────────

    private McpServerFeatures.SyncPromptSpecification memoryOverviewPrompt() {
        var prompt = new McpSchema.Prompt(
                "memory_overview",
                "Get a full overview of the knowledge graph: stats, top topics, hub notes, and priorities",
                List.of()
        );
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, req) ->
                new McpSchema.GetPromptResult(
                        "Knowledge graph overview",
                        List.of(new McpSchema.PromptMessage(
                                McpSchema.Role.USER,
                                new McpSchema.TextContent(render("memory_overview", Map.of()))
                        ))
                )
        );
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

