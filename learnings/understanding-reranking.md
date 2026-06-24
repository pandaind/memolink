# Understanding Cross-Encoder Reranking in MemoLink

This document explains what two-stage retrieval is, why it was added to MemoLink, how it is implemented, and what to tune when you want to use it.

> [!NOTE]
> Cross-encoder reranking is **off by default**. Enable it with `MEMOLINK_RERANKER_ENABLED=true` (env var) or `memolink.reranker.enabled: true` (application.yml).

---

## The Problem: Why Hybrid Search Alone Is Not Enough

MemoLink's `hybridSearch` (Stage 1) uses:
- **BM25** (Lucene keyword ranking) — great at exact keyword matches
- **KNN cosine similarity** (bi-encoder vectors) — great at thematic similarity
- **Importance + recency boosts** — hand-tuned heuristics

These signals are combined with a weighted formula:
```
score = 0.2 × norm(bm25) + 0.8 × norm(knn) + importance_boost + recency_boost
```

The problem: a **bi-encoder** encodes query and document *independently*. It compresses an entire document into a single 384-dimensional vector. Complex, multi-clause queries lose nuance in this compression. Two notes that are vaguely "in the same topic area" will rank equally, even if one actually answers the query and the other doesn't.

---

## The Solution: Cross-Encoder Reranking (Stage 2)

A **cross-encoder** takes a `(query, passage)` pair as a *single joint input* and attends to every word in both simultaneously via BERT self-attention. It outputs a single relevance logit — not a vector — which is far more discriminative.

The trade-off: the cross-encoder cannot pre-encode documents. It must run at query time for every candidate. MemoLink limits this cost by only passing the **top-N Stage-1 candidates** to the cross-encoder.

### Two-Stage Pipeline

```
search_memories("how does disk mode persist the lucene index?")
        │
        ▼
Stage 1 — hybridSearch (unchanged):
  BM25 + KNN → top-40 candidates
  apply importance + recency boosts
  sort by heuristic score
        │
        ▼  (when memolink.reranker.enabled=true)
Stage 2 — CrossEncoderService.rerank():
  for each of the 40 candidates:
    score = cross_encoder(query, title + first 512 chars of body)
  sort by score descending
  return top-10
        │
        ▼
LLM receives 10 precisely ranked memories
```

The same two-stage pattern applies to `ask_vault`:
- Stage 1: KNN chunk search → top-20 paragraph chunks
- Stage 2: cross-encoder scores each `(query, chunk_text)` pair → top-5

---

## Implementation Details

### `CrossEncoderService`
`ist.logic.core.service.CrossEncoderService`

| Method | Description |
|---|---|
| `CrossEncoderService(Path modelDir)` | Async model load — returns immediately |
| `float score(String query, String passage)` | Single `(q, p)` pair inference. Returns raw logit. |
| `<T> List<T> rerank(query, candidates, textExtractor, topK)` | Scores all candidates, returns top-K sorted by score |
| `boolean isAvailable()` | False until model loaded; callers must check |
| `void awaitReady(long timeoutMs)` | Blocks for synchronous startup |
| `void close()` | Releases OrtSession |

Key design: if `isAvailable()` is false, `rerank()` returns the original list unchanged. Callers never need to check — they always pass the reranker and the fallback is transparent.

### `BertTokenizer.encodePair(query, passage, maxLen)`

The cross-encoder uses the **same BERT tokenizer** as the bi-encoder — no new dependency. `encodePair` produces:

```
[CLS] query_tokens [SEP] passage_tokens [SEP]
 ^                   ^                   ^
 type=0             type=0              type=1
```

The 512-token budget is split: up to `budget/2` tokens for the query, the rest for the passage.

### `GraphSearchService` overloads

Two new method signatures were added, keeping the old ones intact:

```java
// hybridSearch with optional reranker
hybridSearch(query, embeddingService, maxResults, metadataLookup, reranker)

// searchChunks with optional reranker
searchChunks(queryEmbedding, maxResults, originalQuery, reranker, chunkTextSupplier)
```

When `reranker == null` or `reranker.isAvailable() == false`, the behaviour is **identical** to before. No regression risk.

### Wiring

| Layer | Where |
|---|---|
| `memolink-mcp-server` | `MemoLinkMcpConfig` creates `CrossEncoderService` bean (`@ConditionalOnProperty`). `MemoLinkMcpTools` receives `Optional<CrossEncoderService>`. |
| `memolink-spring-ai-starter` | `MemoLinkAiAutoConfiguration` creates `CrossEncoderService` bean (`@ConditionalOnProperty`). `MemoLinkAiTools` receives it as a nullable field. |

The `@ConditionalOnProperty` ensures the bean is **never created** when the toggle is off — zero startup cost, zero latency impact.

---

## Configuration Reference

```yaml
# application.yml
memolink:
  reranker:
    enabled: false             # flip to true to activate
    candidate-multiple: 4      # Stage 1 fetches maxResults × 4 candidates
    max-candidates: 20         # hard cap sent to cross-encoder
```

| Env var | Default | Description |
|---|---|---|
| `MEMOLINK_RERANKER_ENABLED` | `false` | Master toggle |
| `MEMOLINK_RERANKER_CANDIDATE_MULTIPLE` | `4` | Overretrieval multiplier |
| `MEMOLINK_RERANKER_MAX_CANDIDATES` | `20` | Hard cap on cross-encoder inputs |

### Tuning tips

- **`candidate-multiple: 4`** — the reranker needs enough material to reorder. Too low (1–2) and Stage 1 already did all the filtering; too high (8+) and latency grows linearly.
- **`max-candidates: 20`** — at ~15ms per candidate on a modern CPU, 20 = ~300ms. Reduce to 10 for sub-150ms on slower hardware.
- **Memory**: the cross-encoder ONNX model uses ~87MB of native off-heap memory. Increase `MEMOLINK_MEMORY_LIMIT` in `docker-compose.yml` to `1.5g` when running both headroom and reranker simultaneously.

---

## Model Details

| Property | Value |
|---|---|
| Model | `cross-encoder/ms-marco-MiniLM-L-6-v2` |
| Training data | MS MARCO passage ranking (human-labelled query-passage relevance) |
| ONNX size | ~87MB |
| Output | Single relevance logit — no fixed range; higher = more relevant |
| Tokenizer | BERT WordPiece — shared with bi-encoder, no extra files |
| Cache location | `~/.memolink/models/ms-marco-MiniLM-L6-v2/` |

The model is extracted from the jar to the local cache on first start. Subsequent starts skip the copy if files already exist.

---

## Summary for Java Developers

If you need to extend or modify the reranker:

1. **Different model**: swap the ONNX file and update `RERANKER_RESOURCE_BASE` / `RERANKER_CACHE_SUBDIR` constants in `MemoLinkMcpConfig`. The inference code in `CrossEncoderService` handles any single-output `[1, 1]` or `[1]` BERT cross-encoder.
2. **Passage construction**: currently `(title + first 512 chars of body)` for `hybridSearch`. Change the `textExtractor` lambda in `MemoLinkMcpTools.search_memories` to adjust what text the reranker sees.
3. **Latency vs quality**: lower `max-candidates` for speed, raise `candidate-multiple` for better recall before reranking.
4. **Disable per-tool**: set `reranker` to null in the specific tool call to opt out without touching the toggle.
