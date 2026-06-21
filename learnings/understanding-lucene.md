# Understanding Lucene in MemoLink

While the Knowledge Graph is great for traversing relationships, users still need to be able to "search" their notes by typing in a query. 

To power this, `memolink-core` uses **Apache Lucene**, an extremely powerful, open-source search engine library written in Java. In fact, Lucene is the engine that powers massive enterprise search platforms like Elasticsearch and Apache Solr!

In MemoLink, Lucene is wrapped by the **`GraphSearchService`** class. Here is exactly how it is configured and how it works:

## 1. The Indexes (Where data is stored)

Lucene doesn't scan your files every time you search. Instead, it reads them once and builds an **Inverted Index** (like the index at the back of a textbook). 

MemoLink builds two separate directories (indexes) using Lucene:
1. **The Main Index**: Stores full notes (`MdFileMetadata`).
2. **The Chunks Index**: Stores paragraph-level chunks of notes for precise AI-vector lookups.

These indexes can be created entirely in RAM (`ByteBuffersDirectory`) for quick tests, or stored on disk (`FSDirectory`) for persistence.

## 2. Text Tokenization (`StandardAnalyzer`)
When text is passed into Lucene, it must be tokenized (split into words, lowercased, punctuation removed). MemoLink uses the `StandardAnalyzer` for this. 

For example, `"Hello, World!"` becomes the tokens `["hello", "world"]`.

## 3. The Search Modes

`GraphSearchService` supports three powerful search modes:

### Mode A: Keyword Search (BM25)
This is your standard "type a word, get results" search. 
MemoLink maps different parts of a markdown file to specific **Fields** in a Lucene `Document`. Because some fields are more important than others, they are given **Boost Multipliers**:

- `title` (Boost: x3.0)
- `headings` (Boost: x2.0)
- `tags` (Boost: x2.0)
- `folder` (Boost: x2.0 - allows searching by the folder path!)
- `content` (Boost: x1.0 - the raw text of the note)

When you search for "java", Lucene uses the `MultiFieldQueryParser` to look across all these fields. If "java" is in the title, it scores much higher than if it's buried in the content!

### Mode B: Semantic Search (KNN Vectors)
MemoLink doesn't just do text-matching; it understands meaning. 
When the `EmbeddingService` generates a float array (vector) representing the meaning of a note, Lucene stores it using a `KnnFloatVectorField`. 

When you search for "how to connect to a database", the query is converted into a vector. Lucene then runs a `KnnFloatVectorQuery` to find the notes whose vectors are mathematically closest (using **Cosine Similarity**) to your query vector, even if they don't contain the exact words you typed!

### Mode C: Hybrid Search (The Magic Recipe)
This is the main entry point used by the application. It combines BM25 (exact keyword match) with KNN (semantic meaning) and metadata boosting to get the perfect results.

The formula looks like this:
`final_score = (0.2 * normalized_BM25) + (0.8 * normalized_KNN) + Metadata_Boosts`

**What are the Metadata Boosts?**
MemoLink modifies the final score based on two extra factors:
1. **Importance**: If a note has `importance: 10` in its YAML frontmatter, it gets an artificial score boost (up to +0.10).
2. **Recency**: Notes that were opened recently get a decay boost (up to +0.05) that slowly fades to 0 over the course of one week.

## Summary for Java Developers
In this project, Lucene is essentially a high-performance database optimized for text and vectors. 
When a markdown file is created or updated:
1. It is converted into a Lucene `Document`.
2. The text is analyzed and indexed.
3. The AI embeddings are saved as `KnnFloatVectorField`s.
4. When queried, `GraphSearchService` queries Lucene to instantly return a combined semantic and keyword-based result!
