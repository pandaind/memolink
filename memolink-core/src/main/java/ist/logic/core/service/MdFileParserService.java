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

    public MdFileMetadata parse(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String fileName = filePath.getFileName().toString();
        String title = deriveTitle(fileName, content);

        Node document = parser.parse(content);

        Set<String> wikiLinks = new LinkedHashSet<>();
        Set<String> headings  = new LinkedHashSet<>();

        NodeVisitor visitor = new NodeVisitor(
            new VisitHandler<>(WikiLink.class, node -> {
                // getPageRef() returns only the page reference, excluding display text after |
                String pageRef = node.getPageRef().toString().trim();
                if (!pageRef.isEmpty()) {
                    wikiLinks.add(normalizeMdFileId(pageRef));
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

        return new MdFileMetadata(fileName, title, content, filePath,
                wikiLinks, tags, keywords, headings);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String deriveTitle(String fileName, String content) {
        for (String line : content.lines().limit(15).toList()) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
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
     * e.g. "Spring Boot" → "spring-boot.md"
     */
    public static String normalizeMdFileId(String raw) {
        String normalized = raw.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9.-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (!normalized.endsWith(".md")) {
            normalized = normalized + ".md";
        }
        return normalized;
    }
}
