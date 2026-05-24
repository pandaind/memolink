package ist.logic.core.model;

import java.util.List;

/**
 * Structured view of a single markdown note returned to agents/tools.
 * Avoids sending raw markdown syntax tokens to the LLM context.
 */
public record NoteDetail(
        String       id,
        String       title,
        List<String> tags,
        List<String> headings,
        List<String> wikiLinks,
        String       body
) {
    /** Build a {@code NoteDetail} from the parsed metadata. */
    public static NoteDetail from(MdFileMetadata m) {
        return new NoteDetail(
                m.getId(),
                m.getTitle(),
                List.copyOf(m.getTags()),
                List.copyOf(m.getHeadings()),
                List.copyOf(m.getWikiLinks()),
                stripMarkdownChrome(m.getContent())
        );
    }

    /**
     * Remove the leading H1 title line and inline tag line so the body
     * contains only the prose content and Related section.
     */
    private static String stripMarkdownChrome(String content) {
        StringBuilder sb = new StringBuilder(content.length());
        boolean pastHeader = false;
        for (String line : content.lines().toList()) {
            if (!pastHeader) {
                // Skip the first H1 heading
                if (line.startsWith("# ")) { pastHeader = true; continue; }
                // Skip a line that is only #tags (no prose)
                if (line.matches("(#[a-zA-Z][a-zA-Z0-9_-]*\\s*)+")) { continue; }
            }
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }
}
