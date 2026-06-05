package ist.logic.core.service;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Computes 384-dimensional sentence embeddings using
 * sentence-transformers/all-MiniLM-L6-v2 (ONNX) via DJL.
 *
 * modelDir must contain model.onnx + tokenizer.json.
 * MemoLinkMcpConfig extracts these from classpath:models/all-MiniLM-L6-v2/
 * into ~/.memolink/models/all-MiniLM-L6-v2/ on first startup.
 *
 * If loading fails, isAvailable() returns false and embed() returns null —
 * all callers fall back to keyword-only search without crashing.
 */
public class EmbeddingService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    public  static final int    DIMENSIONS = 384;

    private ZooModel<String, float[]>  model;
    private Predictor<String, float[]> predictor;
    private boolean                    available = false;

    /** Pass null to disable semantic search entirely. */
    public EmbeddingService(Path modelDir) {
        if (modelDir == null) {
            log.warn("No model directory provided — semantic search disabled.");
            return;
        }
        try {
            log.info("Loading sentence-embedding model from: {}", modelDir);
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelPath(modelDir)
                    .optModelName("model")
                    .optEngine("OnnxRuntime")
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .optArgument("normalize", true)
                    .optArgument("pooling", "mean")
                    .build();
            model     = criteria.loadModel();
            predictor = model.newPredictor();
            available = true;
            log.info("Sentence-embedding model loaded successfully.");
        } catch (ModelNotFoundException | MalformedModelException | IOException e) {
            log.warn("Embedding model failed to load — semantic search disabled. Cause: {}", e.getMessage());
        }
    }

    /** Returns a 384-dim embedding for text, or null if unavailable. */
    public float[] embed(String text) {
        if (!available || text == null || text.isBlank()) return null;
        try {
            return predictor.predict(text.length() > 2048 ? text.substring(0, 2048) : text);
        } catch (TranslateException e) {
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

    public boolean isAvailable()  { return available; }
    public int     getDimensions() { return DIMENSIONS; }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model     != null) model.close();
    }
}
