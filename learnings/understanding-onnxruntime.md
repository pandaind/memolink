# Understanding ONNX Runtime in MemoLink

If you look at the dependencies for `memolink-core`, you'll see `com.microsoft.onnxruntime`. This is one of the most important pieces of the puzzle for enabling MemoLink's AI capabilities.

Here is a breakdown of what it is, why it was chosen, and exactly how it is used in both `EmbeddingService` (bi-encoder) and `CrossEncoderService` (cross-encoder reranker).

## What is ONNX Runtime?
**ONNX (Open Neural Network Exchange)** is an open standard format for machine learning models. It allows models trained in Python frameworks (like PyTorch or TensorFlow) to be exported into a universal `.onnx` file.

**ONNX Runtime** is the high-performance inference engine built by Microsoft that runs these `.onnx` models. It has bindings for many languages, including Java.

In `memolink-core`, ONNX Runtime powers **two models**:

| Model | File | Purpose |
|---|---|---|
| `all-MiniLM-L6-v2` | `EmbeddingService` | Bi-encoder — generates 384-dim embeddings for notes and queries |
| `cross-encoder/ms-marco-MiniLM-L-6-v2` | `CrossEncoderService` | Cross-encoder reranker — scores `(query, passage)` pairs |

## Why use ONNX in Java?
You might wonder: *Why not just make an API call to OpenAI, or run a Python microservice?*

1. **100% Offline & Private**: By running both models locally, your personal notes never leave your machine.
2. **Zero Network Latency**: Generating embeddings takes milliseconds; cross-encoder scoring adds ~5–15ms per candidate.
3. **No Python Required**: A common headache with AI in Java is trying to bundle Python or heavy Deep Java Library (DJL) engines. By using the raw `ai.onnxruntime` Java API alongside a custom pure-Java `BertTokenizer`, both AI engines bundle perfectly into a standard Spring Boot fat jar.

---

## Model 1 — Bi-encoder: `EmbeddingService`

### Role: Stage 1 (fast retrieval)
The bi-encoder encodes query and documents **independently** into 384-dimensional vectors. Cosine similarity between these vectors gives a fast relevance score. Documents are pre-encoded at index time — only the query is encoded at search time.

### How it works

#### 1. Asynchronous Loading
Models can be large (all-MiniLM-L6-v2 is ~86MB). Loading blocks the thread.
`EmbeddingService` loads the model into an `OrtSession` using a background `CompletableFuture`. Until it finishes loading, the app falls back to pure BM25 keyword search.

#### 2. Tokenization — `BertTokenizer.encode(text, maxLen)`
Neural networks can't read text; they read numbers.
Before hitting ONNX, the text is passed to `BertTokenizer`. This class reads a `tokenizer.json` file and converts `"Hello World"` into three arrays of numbers:
- `input_ids`: The vocabulary IDs of the words (+ `[CLS]` / `[SEP]` special tokens).
- `attention_mask`: Tells the model which tokens are real words vs. empty padding.
- `token_type_ids`: All zeros for single-sequence input.

#### 3. Inference (ONNX Runtime)
These three arrays are converted into `OnnxTensor`s and passed to `ortSession.run(inputs)`.

#### 4. Mean-Pooling & L2-Normalization
- **Mean-Pooling**: Averages all word vectors (ignoring padding) to create a single 384-dim `float[]`.
- **L2-Normalization**: Scales the vector magnitude to 1.0, allowing Lucene to use cosine similarity.

---

## Model 2 — Cross-encoder: `CrossEncoderService`

### Role: Stage 2 (accurate reranking)
A cross-encoder takes a *pair* `(query, passage)` as a **single joint input** and outputs a single relevance logit. It is significantly more accurate than a bi-encoder because it can attend to every word in both texts simultaneously.

**The trade-off:** documents cannot be pre-encoded, so inference must run at search time for every candidate. MemoLink runs it only on the top-N candidates from Stage 1, keeping latency bounded.

### How it works

#### 1. Pair Tokenization — `BertTokenizer.encodePair(query, passage, maxLen)`
The key difference from the bi-encoder is the input format:
```
[CLS] query_tokens [SEP] passage_tokens [SEP]
```
The `encodePair` method was added to `BertTokenizer` to produce this format, with `token_type_ids = 0` for the query segment and `1` for the passage segment — exactly what BERT-based cross-encoders expect.

Budget allocation: the 512-token budget is split roughly 50/50 between query and passage, ensuring neither is truncated entirely.

#### 2. Inference
Same `OrtSession.run(inputs)` pattern as the bi-encoder, but the output is a **single logit** `[1, 1]` tensor — not a 384-dim vector.

#### 3. Reranking
`CrossEncoderService.rerank(query, candidates, textExtractor, topK)` scores all candidates and returns the top-K sorted by logit descending. Higher logit = more relevant.

#### 4. Graceful degradation
If the model fails to load (`isAvailable() = false`), `rerank()` returns the original Stage-1 list unchanged — the caller does not need to check.

---

## Two-Stage Retrieval Pipeline

```
ask_vault("how does disk mode work?")
         │
         ▼
Stage 1 — BM25 + KNN bi-encoder → top-20 candidates  (fast, ~10ms)
         │
         ▼  (when memolink.reranker.enabled=true)
Stage 2 — cross-encoder(query, candidate_text) × 20  (accurate, ~200ms)
         │
         ▼
LLM receives top-5 reranked, most relevant paragraphs
```

---

## Summary

| Concern | Bi-encoder (`EmbeddingService`) | Cross-encoder (`CrossEncoderService`) |
|---|---|---|
| Speed | Fast (pre-computed docs) | Slower (runs at query time) |
| Accuracy | Good | Significantly better |
| Use case | First-pass retrieval | Second-pass reranking |
| Input format | Single text | `(query, passage)` pair |
| Output | 384-dim float[] vector | Single float logit |
| Toggle | Always on (when model present) | `memolink.reranker.enabled=true` |

By combining both, MemoLink achieves **high recall** (bi-encoder finds all candidates fast) with **high precision** (cross-encoder selects the truly relevant ones), entirely inside the JVM, with no external dependencies.
