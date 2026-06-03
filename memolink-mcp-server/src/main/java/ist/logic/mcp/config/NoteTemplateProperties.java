package ist.logic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configures the note rendering template and the optional frontmatter metadata
 * fields that are injected into every note header.
 *
 * Example application.yml:
 * <pre>
 * memolink:
 *   note:
 *     template: classpath:templates/note.mustache
 *     metadata-fields:
 *       - name: created
 *         description: "ISO date when the note was created (auto-set)"
 *         required: false
 *       - name: source
 *         description: "Source URL or reference for the note content"
 *         required: false
 * </pre>
 */
@ConfigurationProperties(prefix = "memolink.note")
public class NoteTemplateProperties {

    /** Classpath or file: URI of the Mustache note template. */
    private String template = "classpath:templates/note.mustache";

    /** Ordered list of extra frontmatter fields included in every rendered note. */
    private List<MetadataField> metadataFields = new ArrayList<>();

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public List<MetadataField> getMetadataFields() { return metadataFields; }
    public void setMetadataFields(List<MetadataField> metadataFields) {
        this.metadataFields = metadataFields;
    }

    public static class MetadataField {
        private String  name;
        private String  description = "";
        private boolean required    = false;

        public String getName()        { return name; }
        public void   setName(String n){ this.name = n; }

        public String getDescription()          { return description; }
        public void   setDescription(String d)  { this.description = d; }

        public boolean isRequired()          { return required; }
        public void    setRequired(boolean r){ this.required = r; }
    }
}
