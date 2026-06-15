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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Computes 384-dimensional sentence embeddings using
 * sentence-transformers/all-MiniLM-L6-v2 (ONNX) with a pure-Java BERT tokenizer.
 *
 * Uses OnnxRuntime Java API directly for inference and BertTokenizer (pure Java,
 * no native libs) for tokenisation — works correctly inside Spring Boot fat jars.
 *
 * Model loading is asynchronous — the constructor returns immediately.
 * isAvailable() is false until loading completes; embed() returns null in the
 * meantime and all search paths fall back to keyword-only BM25.
 */
public class EmbeddingService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    public  static final int  DIMENSIONS = 384;
    private static final int  MAX_SEQ    = 512;

    private volatile BertTokenizer tokenizer;
    private volatile OrtEnvironment ortEnv;
    private volatile OrtSession    ortSession;
    private volatile String        outputName;

    private final AtomicBoolean           available  = new AtomicBoolean(false);
    private final CompletableFuture<Void> loadFuture;

    /** Pass null to disable semantic search entirely. */
    public EmbeddingService(Path modelDir) {
        if (modelDir == null) {
            log.warn("No model directory provided — semantic search disabled.");
            loadFuture = CompletableFuture.completedFuture(null);
            return;
        }

        Path onnxFile = modelDir.resolve("model.onnx");

        loadFuture = CompletableFuture.runAsync(() -> {
            if (!Files.exists(onnxFile)) {
                log.warn("Model file not found at {} — semantic search disabled.", onnxFile);
                return;
            }

            try {
                log.info("Loading sentence-embedding model from: {} (background)", modelDir);

                // Pure-Java tokenizer — reads vocab from tokenizer.json, no native libs
                Path tokenizerJson = modelDir.resolve("tokenizer.json");
                tokenizer = new BertTokenizer(tokenizerJson);

                // OnnxRuntime session — direct Java API, no DJL engine system
                ortEnv     = OrtEnvironment.getEnvironment();
                ortSession = ortEnv.createSession(onnxFile.toString(), new OrtSession.SessionOptions());

                // Prefer pre-pooled "sentence_embedding" output; fall back to "last_hidden_state"
                outputName = ortSession.getOutputNames().stream()
                        .filter(n -> n.equals("sentence_embedding"))
                        .findFirst()
                        .orElse(ortSession.getOutputNames().stream()
                                .filter(n -> n.equals("last_hidden_state"))
                                .findFirst()
                                .orElse(ortSession.getOutputNames().iterator().next()));

                available.set(true);
                log.info("Sentence-embedding model ready — semantic search enabled. Output: {}", outputName);

            } catch (Throwable e) {
                System.err.println("[EmbeddingService] FAILED: " + e.getClass().getName() + ": " + e.getMessage());
                log.warn("Embedding model failed to load — semantic search disabled. {}: {}",
                         e.getClass().getSimpleName(), e.getMessage());
            }
        });
    }

    /** Returns a 384-dim L2-normalised embedding, or null if not ready. */
    public float[] embed(String text) {
        if (!available.get() || text == null || text.isBlank()) return null;
        try {
            String input = text.length() > 2048 ? text.substring(0, 2048) : text;
            long[][] encoded = tokenizer.encode(input, MAX_SEQ);

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
                    if (!(outVal instanceof OnnxTensor outTensor)) return null;

                    long[] shape = outTensor.getInfo().getShape();
                    float[] embedding;
                    if (shape.length == 2) {
                        // [1, 384] — pre-pooled sentence embedding
                        embedding = ((float[][]) outTensor.getValue())[0];
                    } else {
                        // [1, seq_len, 384] — mean-pool over token dimension
                        embedding = meanPool(((float[][][]) outTensor.getValue())[0], mask);
                    }
                    return l2Normalize(embedding);
                }
            }
        } catch (OrtException e) {
            log.debug("Embedding failed: {}", e.getMessage());
            return null;
        }
    }

    /** Embeds title + first 512 chars of body. */
    public float[] embedNote(String title, String body) {
        String text = (title == null ? "" : title.trim()) + " " +
                      (body   == null ? "" : body.substring(0, Math.min(body.length(), 512)));
        return embed(text.trim());
    }

    public boolean isAvailable()  { return available.get(); }
    public int     getDimensions() { return DIMENSIONS; }

    /**
     * Blocks until the model finishes loading (or timeoutMs elapses).
     */
    public void awaitReady(long timeoutMs) {
        try {
            loadFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
    }

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

    private static float[] meanPool(float[][] hidden, long[] mask) {
        int dim = hidden[0].length;
        float[] out = new float[dim];
        long total = 0;
        for (int i = 0; i < hidden.length; i++) {
            if (mask[i] != 0L) {
                for (int d = 0; d < dim; d++) out[d] += hidden[i][d];
                total++;
            }
        }
        if (total > 0) for (int d = 0; d < dim; d++) out[d] /= total;
        return out;
    }

    private static float[] l2Normalize(float[] v) {
        double sumSq = 0;
        for (float x : v) sumSq += (double) x * x;
        float norm = (float) Math.sqrt(sumSq);
        if (norm > 1e-9f) for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }
}
