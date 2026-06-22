# Understanding Flexmark in MemoLink

MemoLink's whole job is to understand Markdown notes. To do that, it needs to actually "read" Markdown in a structured way — not just as raw text. That is where **Flexmark** comes in.

## What is Flexmark?

**flexmark-java** (`com.vladsch.flexmark`) is a powerful, high-performance Markdown parser and renderer written in Java. It parses Markdown text and turns it into an **AST (Abstract Syntax Tree)** — a tree of Java objects, where each object represents a piece of the document (a heading, a paragraph, a link, a code block, etc.).

Think of it like this:
- **Raw Markdown text** → `"# Hello\n[[Java]] is great #programming"`
- **After Flexmark** → A tree of objects: `[Heading("Hello"), Paragraph → [WikiLink("Java"), Text(" is great"), Tag("programming")]]`

Once you have the AST, you can walk through it and extract exactly what you want, instead of trying to guess the structure with fragile regular expressions.

## Why Flexmark? Why Not Just Regex?

You might ask: *"We already use Regex in the code for tags (`#tag`) — why not do everything with Regex?"*

Great question. Regex is used for simple, flat patterns (like hashtags that look like `#word`), but Markdown has nested, hierarchical structure:
- A heading (`# H1`) followed by a paragraph.
- Links embedded inside paragraphs (`[[Note Name]]`).
- Code blocks that might contain characters that look like tags or links but should be ignored.

Trying to parse all of this with Regex is extremely brittle and breaks on edge cases. Flexmark solves this correctly by understanding the full Markdown grammar. It also correctly ignores content inside fenced code blocks (` ``` ... ``` `), which Regex would incorrectly match as headings or links.

## What Exactly is Used in `MemoLink`?

Only **two** Flexmark artifacts are declared in `memolink-core`'s `pom.xml`:

| Artifact | Purpose |
|---|---|
| `flexmark` | The core parser for standard Markdown (headings, paragraphs, bold, etc.) |
| `flexmark-ext-wikilink` | Extension that adds support for Obsidian-style `[[Wiki Links]]` |

The standard Markdown spec does NOT include `[[Wiki Links]]`. The extension teaches the parser to recognize them as first-class AST nodes instead of treating them as plain text.

---

## How it's Used in `MdFileParserService.java`

All the Flexmark logic lives inside the `MdFileParserService` class. Here is a step-by-step walkthrough:

### Step 1: Setting Up the Parser (One-Time Initialization)

```java
// In the constructor
MutableDataSet options = new MutableDataSet();
options.set(Parser.EXTENSIONS, List.of(WikiLinkExtension.create()));
this.parser = Parser.builder(options).build();
```

The `Parser` is created once and reused for all files (it's thread-safe and expensive to create). The key setup step is registering the `WikiLinkExtension` so the parser understands `[[links]]`. Without this, `[[Spring Boot]]` would just be parsed as text containing square brackets.

### Step 2: Parsing a File into an AST

```java
String content = Files.readString(filePath);
Node document = parser.parse(content);
```

`parser.parse(content)` is the main call. It takes the raw Markdown `String` and returns a `Node` — the root of the entire document's AST. Everything is now a tree of Java objects.

### Step 3: Walking the AST with a `NodeVisitor`

This is the core of how MemoLink extracts data. The `NodeVisitor` API is a classic **Visitor Design Pattern** — you register handlers for the specific node types you care about, and Flexmark calls them as it walks the tree:

```java
NodeVisitor visitor = new NodeVisitor(

    // Handler for [[Wiki Link]] nodes
    new VisitHandler<>(WikiLink.class, node -> {
        String pageRef = node.getPageRef().toString().trim(); // "Spring Boot"
        String linkText = node.getText().toString().trim();   // for [[target|type]] syntax
        // ...
    }),

    // Handler for heading nodes (# H1, ## H2, etc.)
    new VisitHandler<>(Heading.class, node -> {
        String text = node.getText().toString().trim(); // "Spring Boot Basics"
        // ...
    })
);

visitor.visit(document); // Walk the entire AST, triggering handlers
```

What gets extracted per file:
- **`wikiLinks`** (`Set<String>`): The normalized ID of every `[[link]]` in the note. E.g., `[[Spring Boot]]` → `"spring-boot.md"`.
- **`wikiLinkTypes`** (`Map<String, String>`): The optional relationship type from `[[target|type]]` syntax. E.g., `[[Spring Boot|uses]]` → `{"spring-boot.md" -> "uses"}`. This typed relationship is later stored as the `relationType` on the graph edge.
- **`headings`** (`Set<String>`): All headings in the document. Used as a higher-weight search field in Lucene.

### Step 4: Regex for the Remaining Simple Patterns

After Flexmark handles the structural Markdown parsing, simple flat patterns that don't require understanding the document tree are handled with `java.util.regex`:

- **Tags** (`#programming`): Matched with the regex `(?<![\w/])#([a-zA-Z][a-zA-Z0-9_-]*)`. The negative lookbehind `(?<![\w/])` prevents matching `http://...` URLs.
- **Keywords**: The raw content string is cleaned (code blocks stripped, links unwrapped) and then split by word boundaries to count the most frequent meaningful words.

---

## The Full Data Flow Summary

Here is how a single Markdown file flows through Flexmark and becomes a `MdFileMetadata` object:

```
your-note.md (raw text on disk)
        |
        | Files.readString()
        v
  Raw String (Markdown text)
        |
        | parser.parse(content)       ← Flexmark
        v
  Node document (AST)
        |
        | NodeVisitor walks the tree
        v
  wikiLinks + wikiLinkTypes           ← from WikiLink AST nodes
  headings                            ← from Heading AST nodes
        |
        | Regex on raw content string
        v
  tags                                ← #hashtag pattern
  keywords                            ← top-15 word frequency
        |
        v
  MdFileMetadata (id, title, content, wikiLinks, tags, keywords, headings, ...)
```

This `MdFileMetadata` object is then used by `RelationshipEngine` to build graph edges and by `GraphSearchService` to build the Lucene index.

> [!TIP]
> If you ever need to extract a new piece of data from the Markdown (e.g., task checkboxes `- [ ]`, or external URLs), you would just add a new `VisitHandler<>(TaskListItem.class, ...)` in `MdFileParserService`. Flexmark has AST nodes for virtually every Markdown construct.
