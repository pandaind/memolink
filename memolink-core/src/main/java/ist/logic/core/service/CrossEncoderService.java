package ist.logic.core.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Cross-encoder reranker using {@code cross-encoder/ms-marco-MiniLM-L-6-v2} (ONNX).
 *
 * <p>A cross-encoder takes a {@code (query, passage)} pair together as input and
 * produces a single relevance logit — significantly more accurate than the cosine
 * similarity produced by the bi-encoder, at the cost of higher per-candidate latency.
 *
 * <p>This service is used as a <em>second stage</em> in the retrieval pipeline:
 * <ol>
 *   <li>Stage 1 — fast retrieval: BM25 + KNN → top-N candidates</li>
 *   <li>Stage 2 — accurate reranking: cross-encoder scores each candidate → top-K final</li>
 * </ol>
 *
 * <p>Model loading is asynchronous. {@link #isAvailable()} returns false until ready.
 * If the model fails to load, {@link #rerank} returns the original list unchanged —
 * no exception is propagated to the caller.
 *
 * <p>The {@code BertTokenizer} from {@link EmbeddingService} is reused — the cross-encoder
 * uses the same BERT WordPiece vocabulary.
 */
public class CrossEncoderService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderService.class);

    /** Max sequence length for concatenated [CLS] query [SEP] passage [SEP]. */
    private static final int MAX_SEQ = 512;

    private volatile BertTokenizer tokenizer;
    private volatile OrtEnvironment ortEnv;
    private volatile OrtSession    ortSession;
    private volatile String        outputName;

    private final AtomicBoolean           available  = new AtomicBoolean(false);
    private final CompletableFuture<Void> loadFuture;

    /**
     * Begins loading the cross-encoder model asynchronously.
     * Pass {@code null} to keep the service in disabled mode.
     */
    public CrossEncoderService(Path modelDir) {
        if (modelDir == null) {
            log.warn("No model directory provided — cross-encoder reranking disabled.");
            loadFuture = CompletableFuture.completedFuture(null);
            return;
        }

        Path onnxFile = modelDir.resolve("model.onnx");

        loadFuture = CompletableFuture.runAsync(() -> {
            if (!Files.exists(onnxFile)) {
                log.warn("Cross-encoder model not found at {} — reranking disabled.", onnxFile);
                return;
            }
            try {
                log.info("Loading cross-encoder reranker from: {} (background)", modelDir);
                Path tokenizerJson = modelDir.resolve("tokenizer.json");
                tokenizer  = new BertTokenizer(tokenizerJson);
                ortEnv     = OrtEnvironment.getEnvironment();
                ortSession = ortEnv.createSession(onnxFile.toString(), new OrtSession.SessionOptions());
                outputName = ortSession.getOutputNames().iterator().next(); // single logit output
                available.set(true);
                log.info("Cross-encoder reranker ready. Output: {}", outputName);
            } catch (Throwable e) {
                log.warn("Cross-encoder model failed to load — reranking disabled. {}: {}",
                         e.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    /**
     * Returns the raw relevance logit for the given {@code (query, passage)} pair.
     * Higher is more relevant (no fixed scale — use for relative ranking only).
     * Returns {@link Float#NEGATIVE_INFINITY} if the service is not ready.
     */
    public float score(String query, String passage) {
        if (!available.get()) return Float.NEGATIVE_INFINITY;
        try {
            // Encode [CLS] query [SEP] passage [SEP] as a single sequence
            long[][] encoded = tokenizer.encodePair(query, passage, MAX_SEQ);
            long[] ids     = encoded[0];
            long[] mask    = encoded[1];
            long[] typeIds = encoded[2];

            Map<String, OnnxTensor> inputs = new HashMap<>(4);
            try (OnnxTensor tIds   = OnnxTensor.createTensor(ortEnv, new long[][]{ids});
                 OnnxTensor tMask  = OnnxTensor.createTensor(ortEnv, new long[][]{mask});
                 OnnxTensor tTypes = OnnxTensor.createTensor(ortEnv, new long[][]{typeIds})) {

                inputs.put("input_ids",      tIds);
                inputs.put("attention_mask", tMask);
                inputs.put("token_type_ids", tTypes);

                try (OrtSession.Result result = ortSession.run(inputs)) {
                    OnnxValue outVal = result.get(outputName)
                            .orElseThrow(() -> new OrtException("Output '" + outputName + "' not found"));
                    if (!(outVal instanceof OnnxTensor outTensor)) return Float.NEGATIVE_INFINITY;

                    long[] shape = outTensor.getInfo().getShape();
                    // Output shape: [1, 1] — single relevance logit
                    if (shape.length == 2) {
                        return ((float[][]) outTensor.getValue())[0][0];
                    }
                    // Fallback: [1] flat
                    return ((float[]) outTensor.getValue())[0];
                }
            }
        } catch (OrtException e) {
            log.debug("Cross-encoder scoring failed: {}", e.getMessage());
            return Float.NEGATIVE_INFINITY;
        }
    }

    /**
     * Reranks {@code candidates} by scoring each against {@code query} and
     * returning the top {@code topK} in descending relevance order.
     *
     * <p>If the service is not available, the original list (truncated to {@code topK}) is returned
     * unchanged — so callers can always call this without checking {@link #isAvailable()}.
     *
     * @param query         the user's search query
     * @param candidates    candidate items from Stage 1 retrieval
     * @param textExtractor extracts the passage text to score from each candidate
     * @param scoreUpdater  updates the candidate's score with the new logit score
     * @param topK          number of results to return after reranking
     * @param <T>           candidate type
     */
    public <T> List<T> rerank(String query,
                               List<T> candidates,
                               Function<T, String> textExtractor,
                               java.util.function.BiFunction<T, Float, T> scoreUpdater,
                               int topK) {
        if (!available.get() || candidates.isEmpty()) {
            return candidates.size() <= topK ? candidates
                    : new ArrayList<>(candidates.subList(0, topK));
        }

        record Scored<T>(T item, float score) {}

        List<Scored<T>> scored = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            String text  = textExtractor.apply(candidate);
            float  s     = score(query, text == null ? "" : text);
            scored.add(new Scored<>(scoreUpdater.apply(candidate, s), s));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored<T>::score).reversed())
                .limit(topK)
                .map(Scored::item)
                .toList();
    }

    /** Blocks until the model finishes loading (or timeoutMs elapses). */
    public void awaitReady(long timeoutMs) {
        try {
            loadFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
    }

    public boolean isAvailable() { return available.get(); }

    @Override
    public void close() {
        loadFuture.cancel(true);
        try {
            if (ortSession != null) ortSession.close();
        } catch (OrtException e) {
            log.debug("Error closing OrtSession: {}", e.getMessage());
        }
        if (tokenizer != null) tokenizer.close();
    }
}
