package ist.logic.mcp.config;

import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.service.GraphBuilderService;
import ist.logic.core.service.GraphHolder;
import ist.logic.core.service.GraphSearchService;
import ist.logic.core.service.GraphTraversalService;
import ist.logic.core.service.GraphWatchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(NoteTemplateProperties.class)
public class MemoLinkMcpConfig {

    @Value("${memolink.notes-dir:${user.home}/notes}")
    private String notesDir;

    @Bean
    public GraphHolder graphHolder() throws IOException {
        Path rootDir = Path.of(notesDir).toAbsolutePath();
        KnowledgeGraph graph = new GraphBuilderService().build(rootDir);
        GraphSearchService searchService = new GraphSearchService();
        searchService.index(graph.getAllMdFiles());
        return new GraphHolder(graph, searchService);
    }

    @Bean(destroyMethod = "close")
    public GraphWatchService graphWatchService(GraphHolder holder) throws IOException {
        Path rootDir = Path.of(notesDir).toAbsolutePath();
        GraphBuilderService builder = new GraphBuilderService();
        return new GraphWatchService(rootDir, changedPaths -> {
            try {
                KnowledgeGraph newGraph = builder.buildIncremental(holder.getGraph(), changedPaths);
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
}
