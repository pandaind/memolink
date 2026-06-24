package ist.logic.ai;

import ist.logic.ai.tools.MemoLinkAiTools;
import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.service.CrossEncoderService;
import ist.logic.core.service.EmbeddingService;
import ist.logic.core.service.GraphBuilderService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphSearchService;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.GraphWatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import java.util.Optional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Auto-configures the MemoLink knowledge-graph infrastructure and registers
 * {@link MemoLinkAiTools} as a Spring bean so any Spring AI {@code ChatClient}
 * in the consuming application can pick it up via {@code .tools(memoLinkAiTools)}.
 *
 * <p>All beans are conditional on absence, so the consuming application can
 * override any of them by declaring its own bean of the same type.
 *
 * <p>Only activates when the Spring AI {@code @Tool} annotation is on the classpath,
 * ensuring the starter is a no-op if Spring AI is not present.
 */
@AutoConfiguration
@ConditionalOnClass(Tool.class)
@EnableConfigurationProperties(MemoLinkAiProperties.class)
public class MemoLinkAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoLinkAiAutoConfiguration.class);

    private static final String MODEL_RESOURCE_BASE    = "classpath:models/all-MiniLM-L6-v2/";
    private static final String MODEL_CACHE_SUBDIR     = ".memolink/models/all-MiniLM-L6-v2";
    private static final String RERANKER_RESOURCE_BASE = "classpath:models/ms-marco-MiniLM-L6-v2/";
    private static final String RERANKER_CACHE_SUBDIR  = ".memolink/models/ms-marco-MiniLM-L6-v2";

    // ── Beans ─────────────────────────────────────────────────────────────────

    /**
     * Extracts the ONNX embedding model from the classpath into a local cache directory
     * and creates the {@link EmbeddingService}.
     * If the model resources are not on the classpath (e.g. a minimal dependency),
     * the service starts in degraded mode — keyword search still works, semantic search
     * and {@code ask_vault} will return empty results.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public EmbeddingService memoLinkAiEmbeddingService(ResourceLoader resourceLoader) {
        Path cacheDir = extractModelToCache(resourceLoader);
        return new EmbeddingService(cacheDir);
    }

    /**
     * Cross-encoder reranker bean — only created when
     * {@code memolink.reranker.enabled=true}.
     * When the property is false (default) the bean does not exist and
     * {@link MemoLinkAiTools} receives an empty Optional.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "memolink.reranker.enabled", havingValue = "true")
    public CrossEncoderService memoLinkAiCrossEncoderService(ResourceLoader resourceLoader) {
        Path cacheDir = extractRerankerToCache(resourceLoader);
        return new CrossEncoderService(cacheDir);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphHolder memoLinkAiHolder(MemoLinkAiProperties props,
                                        EmbeddingService embeddingService) throws IOException {
        GraphBuilderService builder = new GraphBuilderService();
        boolean useDisk = isUseDisk(props);
        builder.setUseDisk(useDisk);
        Path rootDir = vaultRootDir(props);
        KnowledgeGraph graph = builder.build(rootDir, embeddingService);
        GraphSearchService searchService = new GraphSearchService(useDisk, luceneDir(rootDir));
        searchService.index(graph.getAllMdFiles());

        // Compute missing embeddings asynchronously so startup is not blocked
        Thread.ofVirtual().start(() -> {
            try {
                embeddingService.awaitReady(60_000);
                builder.computeMissingEmbeddings(graph, embeddingService);
                searchService.index(graph.getAllMdFiles());
            } catch (Exception e) {
                log.warn("Background embedding computation failed", e);
            }
        });

        return new GraphHolder(graph, searchService);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "memoLinkAiWatchService")
    public GraphWatchService memoLinkAiWatchService(GraphHolder holder,
                                                    MemoLinkAiProperties props,
                                                    EmbeddingService embeddingService) throws IOException {
        Path rootDir = vaultRootDir(props);
        GraphBuilderService builder = new GraphBuilderService();
        boolean useDisk = isUseDisk(props);
        builder.setUseDisk(useDisk);
        return new GraphWatchService(rootDir, changedPaths -> {
            try {
                KnowledgeGraph newGraph = builder.buildIncremental(
                        holder.getGraph(), changedPaths, embeddingService);
                GraphSearchService newSearch = new GraphSearchService(useDisk, luceneDir(rootDir));
                if (useDisk) {
                    for (Path p : changedPaths) {
                        if (!Files.exists(p)) {
                            String id = rootDir.relativize(p).toString()
                                    .replace(java.io.File.separatorChar, '/');
                            newSearch.deleteFromIndex(id);
                        }
                    }
                }
                newSearch.index(newGraph.getAllMdFiles());
                holder.update(newGraph, newSearch);
            } catch (IOException e) {
                log.warn("Incremental AI graph rebuild failed", e);
            }
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphTraversalService memoLinkAiTraversalService() {
        return new GraphTraversalService();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoLinkAiTools memoLinkAiTools(GraphHolder holder,
                                           GraphTraversalService traversalService,
                                           EmbeddingService embeddingService,
                                           Optional<CrossEncoderService> reranker,
                                           MemoLinkAiProperties props) {
        return new MemoLinkAiTools(holder, traversalService, embeddingService,
                reranker.orElse(null), vaultRootDir(props));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static boolean isUseDisk(MemoLinkAiProperties props) {
        return "disk".equalsIgnoreCase(props.getLucene().getStorage());
    }

    private static Path vaultRootDir(MemoLinkAiProperties props) {
        return Path.of(props.getVaultDir()).toAbsolutePath();
    }

    private static Path luceneDir(Path rootDir) {
        return rootDir.resolve(".memolink").resolve("lucene");
    }

    /**
     * Copies the ONNX model files from the fat-jar classpath into
     * {@code ~/.memolink/models/all-MiniLM-L6-v2/} so the embedding service
     * can load them from a real filesystem path.
     * Skips files that already exist (no re-copy on subsequent restarts).
     */
    private Path extractModelToCache(ResourceLoader resourceLoader) {
        Path cacheDir = Path.of(System.getProperty("user.home")).resolve(MODEL_CACHE_SUBDIR);
        try {
            Files.createDirectories(cacheDir);
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "model.onnx",
                    cacheDir.resolve("model.onnx"));
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "tokenizer.json",
                    cacheDir.resolve("tokenizer.json"));
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
        var resource = loader.getResource(classpathUrl);
        if (!resource.exists()) {
            log.warn("Model resource not found in jar: {}", classpathUrl);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Extracted {} ({} bytes)", target.getFileName(), Files.size(target));
        }
    }

    private Path extractRerankerToCache(ResourceLoader resourceLoader) {
        Path cacheDir = Path.of(System.getProperty("user.home")).resolve(RERANKER_CACHE_SUBDIR);
        try {
            Files.createDirectories(cacheDir);
            copyIfAbsent(resourceLoader, RERANKER_RESOURCE_BASE + "model.onnx",
                    cacheDir.resolve("model.onnx"));
            copyIfAbsent(resourceLoader, RERANKER_RESOURCE_BASE + "tokenizer.json",
                    cacheDir.resolve("tokenizer.json"));
            log.info("Cross-encoder reranker model ready at: {}", cacheDir);
            return cacheDir;
        } catch (IOException e) {
            log.warn("Could not extract reranker model — reranking disabled. Cause: {}", e.getMessage());
            return null;
        }
    }
}
