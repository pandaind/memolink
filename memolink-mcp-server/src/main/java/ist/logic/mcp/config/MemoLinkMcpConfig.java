package ist.logic.mcp.config;

import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.service.EmbeddingService;
import ist.logic.core.service.GraphBuilderService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphSearchService;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.GraphWatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
@Configuration
@EnableConfigurationProperties({NoteTemplateProperties.class, AuthProperties.class})
public class MemoLinkMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoLinkMcpConfig.class);

    private static final String MODEL_RESOURCE_BASE = "classpath:models/all-MiniLM-L6-v2/";
    private static final String MODEL_CACHE_DIR     = ".memolink/models/all-MiniLM-L6-v2";

    /** Version embedded in the tokenizers-0.31.0.jar native/lib/tokenizers.properties. */
    private static final String TOKENIZERS_VERSION  = "0.20.3-0.31.0";
    private static final String DJL_TOKENIZERS_CACHE = ".djl.ai/tokenizers";

    @Value("${memolink.notes-dir:${user.home}/notes}")
    private String notesDir;

    @Bean(destroyMethod = "close")
    public EmbeddingService embeddingService(ResourceLoader resourceLoader) {
        // Extract model files synchronously (no-op if already on disk — fast).
        // This ensures cacheDir contains model.onnx before EmbeddingService starts polling.
        Path cacheDir = extractModelToCache(resourceLoader);
        // Extract & register the HuggingFace tokenizer native lib so that
        // LibUtils.<clinit> uses it directly (bypassing Spring Boot nested-jar
        // URL scheme which breaks Properties.load() for native/lib/*.properties).
        setupTokenizerNativeLib(resourceLoader);
        return new EmbeddingService(cacheDir);
    }

    @Bean
    public GraphHolder graphHolder(EmbeddingService embeddingService) throws IOException {
        // Wait for the ONNX model to finish loading before building the graph so
        // that embedAll() can compute and store vector embeddings for every note.
        embeddingService.awaitReady(60_000);
        Path rootDir = Path.of(notesDir).toAbsolutePath();
        KnowledgeGraph graph = new GraphBuilderService().build(rootDir, embeddingService);
        GraphSearchService searchService = new GraphSearchService();
        searchService.index(graph.getAllMdFiles());
        return new GraphHolder(graph, searchService);
    }

    @Bean(destroyMethod = "close")
    public GraphWatchService graphWatchService(GraphHolder holder,
                                               EmbeddingService embeddingService) throws IOException {
        Path rootDir = Path.of(notesDir).toAbsolutePath();
        GraphBuilderService builder = new GraphBuilderService();
        return new GraphWatchService(rootDir, changedPaths -> {
            try {
                KnowledgeGraph newGraph = builder.buildIncremental(
                        holder.getGraph(), changedPaths, embeddingService);
                GraphSearchService newSearch = new GraphSearchService();
                newSearch.index(newGraph.getAllMdFiles());
                holder.update(newGraph, newSearch);
            } catch (IOException ignored) {}
        });
    }

    @Bean
    public GraphTraversalService graphTraversalService() {
        return new GraphTraversalService();
    }

    @Bean
    public Path mdGraphNotesDir() {
        return Path.of(notesDir).toAbsolutePath();
    }

    // ── Model extraction ──────────────────────────────────────────────────────

    /**
     * Extracts and registers the HuggingFace tokenizer native library so that
     * {@code LibUtils.<clinit>} can load it even inside a Spring Boot fat jar
     * (where Spring Boot's nested-jar URL scheme prevents reading the
     * {@code native/lib/tokenizers.properties} version file normally).
     *
     * <p>Sets the {@code ai.djl.huggingface.native_helper} system property to
     * the absolute path of the extracted dylib.  LibUtils checks this property
     * first and calls {@code System.load(path)} directly, bypassing version detection.
     */
    private void setupTokenizerNativeLib(ResourceLoader resourceLoader) {
        String os   = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean isMac  = os.contains("mac") || os.contains("darwin");
        boolean isArm  = arch.contains("aarch64") || arch.contains("arm");
        String libName = isMac ? "libtokenizers.dylib" : "libtokenizers.so";

        // 1. Look for an already-cached lib in ~/.djl.ai/tokenizers/ (any sub-dir)
        Path djlTokenizerCache = Path.of(System.getProperty("user.home")).resolve(DJL_TOKENIZERS_CACHE);
        Path cached = findCachedLib(djlTokenizerCache, libName);

        if (cached != null) {
            System.setProperty("ai.djl.huggingface.native_helper", cached.toString());
            log.info("Tokenizer native lib found in cache: {}", cached);
            return;
        }

        // 2. Not yet cached — extract from fat-jar classpath to a versioned cache dir.
        // Jar-internal path: native/lib/{os-arch}/cpu/{libName}
        String osArch = isMac ? (isArm ? "osx-aarch64" : "osx-x86_64")
                               : (isArm ? "linux-aarch64" : "linux-x86_64");
        String jarResource = "classpath:native/lib/" + osArch + "/cpu/" + libName;
        Path cacheDir  = djlTokenizerCache.resolve(TOKENIZERS_VERSION + "-cpu-" + osArch);
        Path libTarget = cacheDir.resolve(libName);
        try {
            Files.createDirectories(cacheDir);
            copyIfAbsent(resourceLoader, jarResource, libTarget);
            if (Files.exists(libTarget)) {
                System.setProperty("ai.djl.huggingface.native_helper", libTarget.toString());
                log.info("Tokenizer native lib extracted: {}", libTarget);
            }
        } catch (IOException e) {
            log.warn("Could not extract tokenizer native lib — tokenizer may fail. Cause: {}", e.getMessage());
        }
    }

    /** Recursively scans {@code root} for a file matching {@code libName} (max depth 3). */
    private static Path findCachedLib(Path root, String libName) {
        if (!Files.exists(root)) return null;
        try (var stream = Files.find(root, 3, (p, a) -> p.getFileName().toString().equals(libName))) {
            return stream.findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Copies model files from the fat-jar classpath into
     * {@code ~/.memolink/models/all-MiniLM-L6-v2/} so DJL can load them
     * from a real filesystem path.  Skips files that already exist (no re-copy
     * on subsequent restarts).
     */
    private Path extractModelToCache(ResourceLoader resourceLoader) {
        Path cacheDir = Path.of(System.getProperty("user.home"))
                            .resolve(MODEL_CACHE_DIR);
        try {
            Files.createDirectories(cacheDir);
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "model.onnx",       cacheDir.resolve("model.onnx"));
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "tokenizer.json",    cacheDir.resolve("tokenizer.json"));
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "serving.properties",cacheDir.resolve("serving.properties"));
            log.info("Embedding model ready at: {}", cacheDir);
            return cacheDir;
        } catch (IOException e) {
            log.warn("Could not extract embedding model — semantic search disabled. Cause: {}", e.getMessage());
            return null;
        }
    }

    private static void copyIfAbsent(ResourceLoader loader, String classpathUrl, Path target)
            throws IOException {
        if (Files.exists(target)) return;
        Resource resource = loader.getResource(classpathUrl);
        if (!resource.exists()) {
            log.warn("Model resource not found in jar: {}", classpathUrl);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Extracted {} ({} bytes)", target.getFileName(), Files.size(target));
        }
    }
}

