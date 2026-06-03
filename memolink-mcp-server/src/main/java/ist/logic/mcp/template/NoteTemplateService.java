package ist.logic.mcp.template;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import ist.logic.mcp.config.NoteTemplateProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders a note to its markdown string using the configurable Mustache template.
 *
 * Template variables exposed to note.mustache:
 * <ul>
 *   <li>{@code title}              – H1 title</li>
 *   <li>{@code hasTags / tagsLine} – tag line rendered as {@code #tag1 #tag2}</li>
 *   <li>{@code hasBody / body}     – main prose</li>
 *   <li>{@code hasLinks / links}   – list of {@code {ref}} maps for wiki-links</li>
 *   <li>{@code hasFrontmatter / frontmatterEntries} – list of {@code {name, value}} maps</li>
 * </ul>
 */
@Service
public class NoteTemplateService {

    private final NoteTemplateProperties properties;
    private final Template               noteTemplate;

    public NoteTemplateService(NoteTemplateProperties properties,
                               ResourceLoader resourceLoader) throws IOException {
        this.properties = properties;
        var resource = resourceLoader.getResource(properties.getTemplate());
        try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            this.noteTemplate = Mustache.compiler().compile(reader);
        }
    }

    /**
     * Renders a note to its full markdown string.
     *
     * @param title     H1 title
     * @param body      main markdown content
     * @param tags      tag names without {@code #} prefix
     * @param wikiLinks file IDs of related notes (e.g. {@code spring-boot.md})
     * @param metadata  extra frontmatter key→value overrides; {@code null} → auto-defaults only
     */
    public String render(String title,
                         String body,
                         List<String> tags,
                         List<String> wikiLinks,
                         Map<String, String> metadata) {

        Map<String, Object> ctx = new HashMap<>();

        ctx.put("title", title == null ? "Untitled" : title.trim());

        // Tags
        boolean hasTags = tags != null && !tags.isEmpty();
        ctx.put("hasTags", hasTags);
        if (hasTags) {
            ctx.put("tagsLine", tags.stream()
                    .map(t -> "#" + t.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-"))
                    .collect(Collectors.joining(" ")));
        }

        // Body
        boolean hasBody = body != null && !body.isBlank();
        ctx.put("hasBody", hasBody);
        if (hasBody) ctx.put("body", body.trim());

        // Wiki-links
        boolean hasLinks = wikiLinks != null && !wikiLinks.isEmpty();
        ctx.put("hasLinks", hasLinks);
        if (hasLinks) {
            ctx.put("links", wikiLinks.stream()
                    .map(l -> Map.of("ref", l.endsWith(".md") ? l.substring(0, l.length() - 3) : l))
                    .toList());
        }

        // Frontmatter from configured fields
        List<Map<String, String>> entries = buildFrontmatter(metadata);
        ctx.put("hasFrontmatter", !entries.isEmpty());
        ctx.put("frontmatterEntries", entries);

        return noteTemplate.execute(ctx);
    }

    /** Returns the configured metadata field definitions (for use by prompt templates). */
    public List<NoteTemplateProperties.MetadataField> getMetadataFields() {
        return properties.getMetadataFields();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<Map<String, String>> buildFrontmatter(Map<String, String> supplied) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (NoteTemplateProperties.MetadataField field : properties.getMetadataFields()) {
            String value = (supplied != null) ? supplied.get(field.getName()) : null;
            if (value == null || value.isBlank()) {
                value = autoFill(field.getName());
            }
            if (value != null && !value.isBlank()) {
                entries.add(Map.of("name", field.getName(), "value", value));
            }
        }
        return entries;
    }

    /** Auto-fill well-known field names so callers don't have to supply them. */
    private static String autoFill(String fieldName) {
        return switch (fieldName.toLowerCase()) {
            case "created", "date", "created_at" -> LocalDate.now().toString();
            default -> null;
        };
    }
}
