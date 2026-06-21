package ist.logic.ai;

import ist.logic.ai.tools.MemoLinkAiTools;
import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.service.GraphBuilderService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphSearchService;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.GraphWatchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-configures the MemoLink knowledge-graph infrastructure and registers
 * {@link MemoLinkAiTools} as a Spring bean so any Spring AI {@code ChatClient}
 * in the consuming application can pick it up via {@code .tools(memoLinkAiTools)}.
 *
 * <p>All beans are conditional on absence, so the consuming application can
 * override any of them simply by declaring its own bean of the same type.
 *
 * <p>Only activates when the Spring AI {@code @Tool} annotation is on the classpath,
 * ensuring the starter is a no-op if Spring AI is not present.
 */
@AutoConfiguration
@ConditionalOnClass(Tool.class)
@EnableConfigurationProperties(MemoLinkAiProperties.class)
public class MemoLinkAiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoLinkAiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GraphHolder memoLinkAiHolder(MemoLinkAiProperties props) throws IOException {
        GraphBuilderService builder = new GraphBuilderService();
        boolean useDisk = "disk".equalsIgnoreCase(props.getLucene().getStorage());
        builder.setUseDisk(useDisk);
        Path rootDir = Path.of(props.getVaultDir()).toAbsolutePath();
        KnowledgeGraph graph = builder.build(rootDir);
        Path luceneDir = rootDir.resolve(".memolink").resolve("lucene");
        GraphSearchService searchService = new GraphSearchService(useDisk, luceneDir);
        searchService.index(graph.getAllMdFiles());
        return new GraphHolder(graph, searchService);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "memoLinkAiWatchService")
    public GraphWatchService memoLinkAiWatchService(GraphHolder holder,
                                                   MemoLinkAiProperties props) throws IOException {
        Path rootDir = Path.of(props.getVaultDir()).toAbsolutePath();
        GraphBuilderService builder = new GraphBuilderService();
        boolean useDisk = "disk".equalsIgnoreCase(props.getLucene().getStorage());
        builder.setUseDisk(useDisk);
        return new GraphWatchService(rootDir, changedPaths -> {
            try {
                KnowledgeGraph newGraph = builder.buildIncremental(holder.getGraph(), changedPaths);
                Path luceneDir = rootDir.resolve(".memolink").resolve("lucene");
                GraphSearchService newSearch = new GraphSearchService(useDisk, luceneDir);
                if (useDisk) {
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
                log.warn("Incremental AI graph rebuild failed", e);
            }
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphTraversalService mdGraphTraversalService() {
        return new GraphTraversalService();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoLinkAiTools memoLinkAiTools(GraphHolder holder,
                                         GraphTraversalService traversalService,
                                         MemoLinkAiProperties props) {
        Path vaultDir = Path.of(props.getVaultDir()).toAbsolutePath();
        return new MemoLinkAiTools(holder, traversalService, vaultDir);
    }
}
