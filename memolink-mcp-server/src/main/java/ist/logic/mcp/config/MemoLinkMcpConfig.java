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

@Configuration
@EnableConfigurationProperties(NoteTemplateProperties.class)
public class MemoLinkMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoLinkMcpConfig.class);

    private static final String MODEL_RESOURCE_BASE = "classpath:models/all-MiniLM-L6-v2/";
    private static final String MODEL_CACHE_DIR     = ".memolink/models/all-MiniLM-L6-v2";

    @Value("${memolink.notes-dir:${user.home}/notes}")
    private String notesDir;

    @Bean(destroyMethod = "close")
    public EmbeddingService embeddingService(ResourceLoader resourceLoader) {
        Path modelDir = extractModelToCache(resourceLoader);
        return new EmbeddingService(modelDir);
    }

    @Bean
    public GraphHolder graphHolder(EmbeddingService embeddingService) throws IOException {
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

