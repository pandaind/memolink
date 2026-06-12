package ist.logic.core.service;

import ist.logic.core.model.MdFileMetadata;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.ext.wikilink.WikiLinkExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses a single markdown file into a {@link MdFileMetadata}.
 *
 * Extracted signals:
 *   - wiki links  {@code [[link]]}  → wikiLinks set
 *   - inline tags  {@code #tag}     → tags set
 *   - ATX headings                  → headings set
 *   - top-N content keywords        → keywords set (stop-word filtered)
 */
public class MdFileParserService {

    // Matches #tag in body text; ignores headings (#) and URLs (http://#...)
    private static final Pattern TAG_PATTERN =
            Pattern.compile("(?<![\\w/])#([a-zA-Z][a-zA-Z0-9_-]*)");

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]{3,}");
    private static final int MAX_KEYWORDS = 15;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "her",
            "was", "one", "our", "out", "day", "get", "has", "him", "his", "how",
            "its", "may", "new", "now", "old", "see", "two", "who", "did", "she",
            "use", "way", "will", "with", "this", "that", "they", "from", "have",
            "been", "more", "also", "into", "then", "than", "when", "which", "each",
            "just", "like", "some", "time", "very", "what", "know", "take", "come",
            "made", "over", "such", "your", "well", "only", "even", "back", "after",
            "first", "these", "being", "there", "their", "other", "about", "where",
            "could", "would", "should", "those", "while", "much", "many", "data",
            "using", "used", "make", "makes", "need", "needs", "work", "works",
            "note", "notes", "file", "files", "http", "https", "www"
    );

    private final Parser parser;

    public MdFileParserService() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(WikiLinkExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    /**
     * Parse a markdown file using just its filename as the note ID.
     * Use {@link #parse(Path, Path)} when the vault root is known to get full
     * relative-path IDs (e.g. {@code skills/java/spring-ai.md}).
     */
    public MdFileMetadata parse(Path filePath) throws IOException {
        return parseWithId(filePath, filePath.getFileName().toString());
    }

    /**
     * Parse a markdown file and derive its ID as the relative path from
     * {@code vaultRoot} (forward-slash separated, e.g. {@code agents/note.md}).
     *
     * @param filePath  absolute path to the {@code .md} file
     * @param vaultRoot absolute path to the vault root directory
     */
    public MdFileMetadata parse(Path filePath, Path vaultRoot) throws IOException {
        String id = vaultRoot.relativize(filePath)
                .toString()
                .replace(java.io.File.separatorChar, '/');
        return parseWithId(filePath, id);
    }

    private MdFileMetadata parseWithId(Path filePath, String id) throws IOException {
        String content = Files.readString(filePath);
        String fileName = filePath.getFileName().toString();
        String title = deriveTitle(fileName, content);

        Node document = parser.parse(content);

        Set<String> wikiLinks       = new LinkedHashSet<>();
        Map<String, String> wikiLinkTypes = new LinkedHashMap<>();
        Set<String> headings  = new LinkedHashSet<>();

        NodeVisitor visitor = new NodeVisitor(
            new VisitHandler<>(WikiLink.class, node -> {
                // getPageRef() returns only the page reference, excluding display text after |
                String pageRef = node.getPageRef().toString().trim();
                if (!pageRef.isEmpty()) {
                    String normalizedId = normalizeMdFileId(pageRef);
                    wikiLinks.add(normalizedId);
                    // Extract optional relationship type from the link text: [[target|type]]
                    String linkText = node.getText().toString().trim();
                    if (!linkText.isEmpty() && !linkText.equals(pageRef)) {
                        // link text differs from page ref → treat as relation type
                        String relType = linkText.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                        wikiLinkTypes.put(normalizedId, relType);
                    }
                }
            }),
            new VisitHandler<>(Heading.class, node -> {
                String text = node.getText().toString().trim();
                if (!text.isEmpty()) {
                    headings.add(text);
                }
            })
        );
        visitor.visit(document);

        Set<String> tags     = extractTags(content);
        Set<String> keywords = extractKeywords(content);

        MdFileMetadata meta = new MdFileMetadata(id, title, content, filePath,
                wikiLinks, wikiLinkTypes, tags, keywords, headings);

        // Read importance from YAML frontmatter if present
        int importance = parseFrontmatterInt(content, "importance");
        if (importance > 0) meta.setImportance(importance);

        return meta;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String deriveTitle(String fileName, String content) {
        // Skip frontmatter before looking for H1
        boolean inFrontmatter = false;
        boolean frontmatterDone = false;
        for (String line : content.lines().limit(25).toList()) {
            if (!frontmatterDone && line.equals("---")) {
                if (!inFrontmatter) { inFrontmatter = true; continue; }
                else { frontmatterDone = true; continue; }
            }
            if (inFrontmatter && !frontmatterDone) continue;
            if (line.startsWith("# ")) return line.substring(2).trim();
        }
        return fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3)
                : fileName;
    }

    private Set<String> extractTags(String content) {
        Set<String> tags = new LinkedHashSet<>();
        Matcher m = TAG_PATTERN.matcher(content);
        while (m.find()) {
            tags.add(m.group(1).toLowerCase());
        }
        return tags;
    }

    private Set<String> extractKeywords(String content) {
        // Strip markdown formatting to get plain words
        String plain = content
                .replaceAll("```[\\s\\S]*?```", " ")       // fenced code blocks
                .replaceAll("`[^`]+`", " ")                 // inline code
                .replaceAll("!?\\[([^]]*)]\\([^)]*\\)", "$1") // links / images
                .replaceAll("\\[\\[([^]|]*)(?:\\|[^]]*)?]]", "$1") // wiki links
                .replaceAll("[#*_~>|\\[\\](){}]", " ")      // markdown syntax chars
                .toLowerCase();

        Map<String, Integer> freq = new HashMap<>();
        Matcher m = WORD_PATTERN.matcher(plain);
        while (m.find()) {
            String word = m.group();
            if (!STOP_WORDS.contains(word)) {
                freq.merge(word, 1, Integer::sum);
            }
        }

        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_KEYWORDS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Normalise a wiki link target to the note ID format: lower-kebab-case + ".md".
     * Supports subdirectory paths separated by {@code /}, e.g.
     * {@code "agents/My Note"} → {@code "agents/my-note.md"}.
     *
     * <p>Each path segment is independently normalised to kebab-case. Directory
     * traversal ({@code ..}) is rejected by removing any such segments silently.
     *
     * Examples:
     * <ul>
     *   <li>{@code "Spring Boot"}              → {@code "spring-boot.md"}</li>
     *   <li>{@code "agents/My Note"}            → {@code "agents/my-note.md"}</li>
     *   <li>{@code "skills/java/spring-ai.md"} → {@code "skills/java/spring-ai.md"}</li>
     * </ul>
     */
    public static String normalizeMdFileId(String raw) {
        if (raw == null) return "untitled.md";
        // Split on forward or backward slashes to support subdirectory paths
        String[] parts = raw.trim().split("[/\\\\]+");
        List<String> segments = new java.util.ArrayList<>();
        for (String part : parts) {
            // Reject traversal segments silently
            if (part.equals(".") || part.equals("..") || part.isBlank()) continue;
            String seg = part.toLowerCase()
                    .replaceAll("[^a-z0-9.-]+", "-")
                    .replaceAll("-+", "-")
                    .replaceAll("^-|-$", "");
            if (!seg.isBlank()) segments.add(seg);
        }
        if (segments.isEmpty()) return "untitled.md";
        // Last segment gets the .md extension
        String last = segments.get(segments.size() - 1);
        if (!last.endsWith(".md")) {
            segments.set(segments.size() - 1, last + ".md");
        }
        return String.join("/", segments);
    }

    /**
     * Reads an integer value from a YAML frontmatter field.
     * e.g. {@code importance: 8} → 8. Returns 0 if not found or unparseable.
     */
    private static int parseFrontmatterInt(String content, String fieldName) {
        boolean inFrontmatter = false;
        for (String line : content.lines().limit(30).toList()) {
            if (line.equals("---")) {
                if (!inFrontmatter) { inFrontmatter = true; continue; }
                else break;
            }
            if (!inFrontmatter) continue;
            if (line.startsWith(fieldName + ":")) {
                String val = line.substring(fieldName.length() + 1).trim();
                try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }
}
