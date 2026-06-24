package ist.logic.mcp.config;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

@Configuration
@EnableConfigurationProperties({NoteTemplateProperties.class, AuthProperties.class, HeadroomProperties.class, RerankProperties.class})
public class MemoLinkMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoLinkMcpConfig.class);

    private static final String MODEL_RESOURCE_BASE    = "classpath:models/all-MiniLM-L6-v2/";
    private static final String MODEL_CACHE_SUBDIR     = ".memolink/models/all-MiniLM-L6-v2";
    private static final String RERANKER_RESOURCE_BASE = "classpath:models/ms-marco-MiniLM-L6-v2/";
    private static final String RERANKER_CACHE_SUBDIR  = ".memolink/models/ms-marco-MiniLM-L6-v2";

    @Value("${memolink.vault-dir:${user.home}/vault}")
    private String vaultDir;

    @Value("${memolink.lucene.storage:memory}")
    private String luceneStorage;

    // ── Beans ─────────────────────────────────────────────────────────────────

    @Bean(destroyMethod = "close")
    public EmbeddingService embeddingService(ResourceLoader resourceLoader) {
        Path cacheDir = extractModelToCache(resourceLoader);
        return new EmbeddingService(cacheDir);
    }

    /**
     * Cross-encoder reranker bean — only created when
     * {@code memolink.reranker.enabled=true}.
     * When the property is false (default) the bean does not exist and
     * {@link ist.logic.mcp.tools.MemoLinkMcpTools} receives an empty Optional.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "memolink.reranker.enabled", havingValue = "true")
    public CrossEncoderService crossEncoderService(ResourceLoader resourceLoader) {
        Path cacheDir = extractRerankerToCache(resourceLoader);
        return new CrossEncoderService(cacheDir);
    }

    @Bean
    public GraphBuilderService graphBuilderService() {
        GraphBuilderService builder = new GraphBuilderService();
        builder.setUseDisk(isUseDisk());
        return builder;
    }

    @Bean
    public GraphHolder graphHolder(GraphBuilderService builder,
                                   EmbeddingService embeddingService) throws IOException {
        Path rootDir          = vaultRootDir();
        KnowledgeGraph graph  = builder.build(rootDir, embeddingService);
        GraphSearchService searchService = new GraphSearchService(isUseDisk(), luceneDir(rootDir));
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
    public GraphWatchService graphWatchService(GraphHolder holder,
                                               GraphBuilderService builder,
                                               EmbeddingService embeddingService) throws IOException {
        Path rootDir = vaultRootDir();
        return new GraphWatchService(rootDir, changedPaths -> {
            try {
                KnowledgeGraph newGraph = builder.buildIncremental(
                        holder.getGraph(), changedPaths, embeddingService);
                GraphSearchService newSearch = new GraphSearchService(isUseDisk(), luceneDir(rootDir));
                if (isUseDisk()) {
                    for (Path p : changedPaths) {
                        if (!Files.exists(p)) {
                            String id = rootDir.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                            newSearch.deleteFromIndex(id);
                        }
                    }
                }
                newSearch.index(newGraph.getAllMdFiles());
                holder.update(newGraph, newSearch);
            } catch (IOException e) {
                log.warn("Incremental graph rebuild failed after file change", e);
            }
        });
    }

    @Bean
    public GraphTraversalService graphTraversalService() {
        return new GraphTraversalService();
    }

    @Bean
    public Path mdGraphVaultDir() {
        return vaultRootDir();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isUseDisk() {
        return "disk".equalsIgnoreCase(luceneStorage);
    }

    private Path vaultRootDir() {
        return Path.of(vaultDir).toAbsolutePath();
    }

    private static Path luceneDir(Path rootDir) {
        return rootDir.resolve(".memolink").resolve("lucene");
    }

    /**
     * Copies model files from the fat-jar classpath into
     * {@code ~/.memolink/models/all-MiniLM-L6-v2/} so the embedding service
     * can load them from a real filesystem path.
     * Skips files that already exist (no re-copy on subsequent restarts).
     */
    private Path extractModelToCache(ResourceLoader resourceLoader) {
        Path cacheDir = Path.of(System.getProperty("user.home")).resolve(MODEL_CACHE_SUBDIR);
        try {
            Files.createDirectories(cacheDir);
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "model.onnx",    cacheDir.resolve("model.onnx"));
            copyIfAbsent(resourceLoader, MODEL_RESOURCE_BASE + "tokenizer.json", cacheDir.resolve("tokenizer.json"));
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

    /**
     * Copies the cross-encoder model files from the fat-jar classpath into
     * {@code ~/.memolink/models/ms-marco-MiniLM-L6-v2/}.
     * Skips files that already exist (no re-copy on subsequent restarts).
     */
    private Path extractRerankerToCache(ResourceLoader resourceLoader) {
        Path cacheDir = Path.of(System.getProperty("user.home")).resolve(RERANKER_CACHE_SUBDIR);
        try {
            Files.createDirectories(cacheDir);
            copyIfAbsent(resourceLoader, RERANKER_RESOURCE_BASE + "model.onnx",     cacheDir.resolve("model.onnx"));
            copyIfAbsent(resourceLoader, RERANKER_RESOURCE_BASE + "tokenizer.json", cacheDir.resolve("tokenizer.json"));
            log.info("Cross-encoder reranker model ready at: {}", cacheDir);
            return cacheDir;
        } catch (IOException e) {
            log.warn("Could not extract reranker model — reranking disabled. Cause: {}", e.getMessage());
            return null;
        }
    }
}

