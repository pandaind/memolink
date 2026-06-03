package ist.logic.mcp.template;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import ist.logic.mcp.config.NoteTemplateProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and renders Mustache prompt templates from {@code classpath:prompts/<name>.mustache}.
 *
 * Every render automatically injects {@code metadataFields} from the note config
 * so prompt text can reference configured frontmatter fields without hard-coding them.
 *
 * Template variables always available:
 * <ul>
 *   <li>{@code hasMetadataFields} – true when ≥ 1 field is configured</li>
 *   <li>{@code metadataFields}    – list of {@code {name, description, required}}</li>
 * </ul>
 */
@Service
public class PromptTemplateService {

    private final NoteTemplateProperties        noteProperties;
    private final ResourceLoader                resourceLoader;
    private final Map<String, Template>         cache = new ConcurrentHashMap<>();

    public PromptTemplateService(NoteTemplateProperties noteProperties,
                                 ResourceLoader resourceLoader) {
        this.noteProperties = noteProperties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Renders the prompt template for {@code promptName} with the supplied variables.
     *
     * @param promptName  maps to {@code classpath:prompts/<promptName>.mustache}
     * @param vars        caller-supplied template variables
     * @return rendered prompt text
     */
    public String render(String promptName, Map<String, Object> vars) throws IOException {
        Template t = cache.computeIfAbsent(promptName, this::loadTemplate);

        Map<String, Object> ctx = new HashMap<>(vars);

        List<NoteTemplateProperties.MetadataField> fields = noteProperties.getMetadataFields();
        ctx.put("hasMetadataFields", !fields.isEmpty());
        ctx.put("metadataFields", fields.stream()
                .map(f -> Map.of(
                        "name",        f.getName(),
                        "description", f.getDescription(),
                        "required",    f.isRequired()))
                .toList());

        return t.execute(ctx);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Template loadTemplate(String name) {
        var resource = resourceLoader.getResource("classpath:prompts/" + name + ".mustache");
        try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return Mustache.compiler().compile(reader);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load prompt template: " + name + ".mustache", e);
        }
    }
}
