package ist.logic.viewer;

import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.service.GraphBuilderService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphSearchService;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.GraphWatchService;
import ist.logic.viewer.controller.GraphController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Auto-configures the MemoLink knowledge-graph infrastructure and the viewer
 * REST API ({@code /api/graph}, {@code /api/notes/{id}}, {@code /api/search},
 * {@code /api/traverse/{id}}, {@code /api/events}) plus the static Cytoscape.js UI.
 *
 * <p>All beans are conditional on absence so the consuming application can
 * override any of them by declaring its own bean of the same type.
 *
 * <p>Only activates in a web application context (requires Spring MVC on the classpath).
 */
@AutoConfiguration
@ConditionalOnWebApplication
@EnableConfigurationProperties(MemoLinkViewerProperties.class)
public class MemoLinkViewerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GraphRegistry memoLinkViewerRegistry(MemoLinkViewerProperties props) throws IOException {
        Path rootDir = Path.of(props.getVaultDir()).toAbsolutePath();
        KnowledgeGraph graph = new GraphBuilderService().build(rootDir);
        boolean useDisk = "disk".equalsIgnoreCase(props.getLucene().getStorage());
        Path luceneDir = rootDir.resolve(".memolink").resolve("lucene");
        GraphSearchService searchService = new GraphSearchService(useDisk, luceneDir);
        searchService.index(graph.getAllMdFiles());
        return new GraphRegistry(graph, searchService);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "memoLinkViewerWatchService")
    public GraphWatchService memoLinkViewerWatchService(GraphRegistry registry,
                                                       MemoLinkViewerProperties props) throws IOException {
        Path rootDir = Path.of(props.getVaultDir()).toAbsolutePath();
        GraphBuilderService builder = new GraphBuilderService();
        return new GraphWatchService(rootDir, changedPaths -> {
            try {
                KnowledgeGraph newGraph = builder.buildIncremental(registry.getGraph(), changedPaths);
                boolean useDisk = "disk".equalsIgnoreCase(props.getLucene().getStorage());
                Path luceneDir = rootDir.resolve(".memolink").resolve("lucene");
                GraphSearchService newSearch = new GraphSearchService(useDisk, luceneDir);
                newSearch.index(newGraph.getAllMdFiles());
                registry.update(newGraph, newSearch);
            } catch (IOException ignored) {}
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphTraversalService mdGraphTraversalService() {
        return new GraphTraversalService();
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphController graphController(GraphRegistry registry,
                                           GraphTraversalService traversalService) {
        return new GraphController(registry, traversalService);
    }
}
