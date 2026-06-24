# Understanding Score Calculations in MemoLink

MemoLink uses a custom, multi-layered scoring algorithm to rank markdown files based on a user's search query. This document explains the exact math behind the `hybridSearch` scoring system, why it was designed this way, and how it behaves both with and without the optional cross-encoder reranker.

## The Problem with Single-Metric Searches

If you only use **BM25 (Keyword Search)**, searching for "Java Streams" will perfectly match a file that says "Java Streams", but will completely miss a file about "Collections filter and map" because the exact words don't match.

If you only use **KNN (Vector Semantic Search)**, searching for "Java Streams" will match "Collections filter and map" (good!), but it might rank a totally unrelated C# LINQ tutorial highly just because the *concept* of streaming data is similar (bad!).

To fix this, MemoLink uses **Hybrid Search (Reciprocal Rank Fusion)** combined with **Heuristics** to get the best of both worlds, and then optionally applies a **Neural Reranker** for final precision.

---

## Stage 1: The Hybrid Search Formula (Always On)

When you call `search_memories`, MemoLink executes both searches simultaneously and combines their scores using a weighted formula.

### The Raw Formula
```
Final Score = (0.2 × Normalized BM25) + 
              (0.8 × Normalized KNN) + 
              Importance Boost + 
              Recency Boost
```

### 1. Normalization (The Foundation)
Lucene BM25 scores are unbounded (they can be 2.5, 18.9, 104.2, etc.). KNN Cosine Similarity scores are bounded (between 0.0 and 1.0). You cannot add them directly!
- We find the absolute highest BM25 score returned for that specific query (`maxBm25`).
- We divide every BM25 score by `maxBm25`.
- Now, the best BM25 result has a score of `1.0`, and everything else is a fraction of `1.0`. We do the same for KNN.

### 2. Alpha & Beta Weights
- **Alpha (0.2)**: We multiply the normalized BM25 score by 0.2.
- **Beta (0.8)**: We multiply the normalized KNN score by 0.8.

> **Why this ratio?** Through testing, semantic similarity (KNN) is usually closer to human intent when searching personal notes. BM25 acts as a 20% "keyword anchor" to ensure exact matches aren't completely buried by loosely related semantic concepts.

### 3. Importance Boost
Notes have an `importance` frontmatter field ranging from 0 to 10 (defaults to 0, or set by the LLM via `set_memory_importance`).
- Formula: `Importance Boost = importance × 0.01`
- A perfectly important note (10) gets a flat `+0.10` boost to its final score. This is enough to act as a tie-breaker between two highly relevant notes, but not enough to force an irrelevant note to the top of the search results.

### 4. Recency Boost
Notes track a `lastAccessedMs` timestamp. Freshly touched notes are statistically more likely to be what you are looking for.
- Formula: `Recency Boost = Max(0, 0.05 × (1 - (Age in ms / 1 Week in ms)))`
- A note edited 1 second ago gets a `+0.05` boost.
- A note edited 3.5 days ago gets a `+0.025` boost.
- A note edited more than 7 days ago gets `0.0` boost.

---

## Stage 2: The Cross-Encoder Reranker (Optional)

If `MEMOLINK_RERANKER_ENABLED=true` is set, the scoring pipeline changes dramatically at the very end.

### The Limitation of Stage 1
Stage 1 is extremely fast (usually < 10ms), but it relies on a "bi-encoder" (the embedding model squashes your entire note into a single 384-dimensional vector). Complex linguistic nuances are lost. 

### How Reranking Works
1. Stage 1 executes exactly as described above, but it fetches **4x** the number of requested results (e.g., if the LLM asked for 5 results, we fetch the top 20 based on the Stage 1 formula).
2. The reranker takes those top 20 candidates.
3. **It throws the Stage 1 score in the trash.**
4. It feeds the `(User Query, Note Title + first 512 chars)` as a *single joint string* directly into a heavy neural network (`ms-marco-MiniLM-L-6-v2`).
5. The neural network generates a **Logit Score** (an unbounded float predicting relevance based on deep self-attention between the query words and the note words).
6. The 20 candidates are re-sorted entirely by this new Logit score, and the top 5 are returned.

> **Why do it this way?** Running the cross-encoder on all 1000+ files in your vault would take 15+ seconds. By using the Stage 1 formula to instantly filter the vault down to the top 20, and then using the heavy cross-encoder to precisely re-order those 20, you get incredible precision in under 200 milliseconds.

---

## How to Use This Knowledge

1. **When reviewing LLM context**: If you see notes creeping into the context window that aren't strictly relevant, check their `importance` rating. An importance of 10 gives a massive `0.10` bump, which might be overpowering the BM25/KNN math for short queries.
2. **If exact keywords are failing**: If searching for specific acronyms fails because the KNN vector (0.8 weight) is drowning out the BM25 exact match (0.2 weight), you can adjust the `ALPHA` and `BETA` constants in `GraphSearchService.java`.
3. **Debugging the Reranker**: The reranker scores do not look like percentages; they are raw logits. A score of `5.4` might be excellent, while a `-1.2` is terrible. Do not attempt to normalize the reranker output to a 0–1 scale.

---

## Graph Edge Weights (Wiki Links, Tags, Keywords)

You might also notice parsing logic for **Wiki Links** and **Keywords** across the codebase. **These are NOT used in the `search_memories` math.**

Instead, they are used by the `RelationshipEngine.java` to build the **Knowledge Graph**. The Knowledge Graph is used by tools like `get_related_memories` and `traverse_memories` to let the LLM crawl laterally between connected notes.

### Edge Scoring Math

To prevent an $O(n^2)$ explosion, the Relationship Engine only connects notes that share explicit signals. It calculates an "Edge Weight" between any two notes using this formula:

| Signal | Score | Cap |
|---|---|---|
| **Wiki Link** | `+5` | Infinite |
| **Shared Tag** | `+2` per tag | Max `+6` (3 tags) |
| **Shared Keyword** | `+1` per keyword | Max `+5` (5 keywords) |

> **Why the Caps? A Concrete Example**
> Imagine two completely unrelated daily journal entries: `2024-01-01.md` (about a grocery trip) and `2024-05-15.md` (about a dentist appointment). 
> 
> Because they are both daily notes, they might share 5 generic tags: `#journal`, `#daily`, `#personal`, `#todo`, and `#life`.
> 
> **Scenario A (No Caps):**
> - The 5 shared tags would generate `5 tags × 2 = +10` edge weight.
> - Meanwhile, a highly specific, deep dive note on Java (`java_streams.md`) might have a direct, explicit `[[lambda expressions]]` wiki-link to another note, which gives a weight of `+5`.
> - **Result:** The graph would mathematically believe that your grocery trip and your dentist appointment (`+10`) are twice as related as your two highly technical Java notes (`+5`). This would ruin graph traversal!
> 
> **Scenario B (With Caps):**
> - The 5 shared tags hit the cap of 3, resulting in `3 tags × 2 = +6` max edge weight.
> - The explicit `[[lambda expressions]]` link gives `+5`. But usually, explicit linked notes also share a tag (like `#java`) and a keyword (like `streams`), pushing the explicit relationship to `5 + 2 + 1 = 8`.
> - **Result:** Explicit relationships created by the user (`8+`) correctly outrank implicit, generic metadata relationships (`6`).

If `Note A` and `Note B` share 1 tag and 2 keywords, their edge weight is `4`. If they have a direct `[[WikiLink]]`, their weight is at least `5`.

### How it is used
When the LLM calls `get_related_memories(id="Note A")`, it uses the `GraphTraversalService`. That service relies on the Knowledge Graph to instantly return a list of adjacent notes, sorted by these Edge Weights descending. This allows the LLM to easily discover the most strongly connected notes without having to do a fuzzy text search.
