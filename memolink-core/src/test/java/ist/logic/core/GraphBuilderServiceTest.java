package ist.logic.core;

import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.model.MdFileMetadata;
import ist.logic.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuilderServiceTest {

    @TempDir
    Path notesDir;

    private GraphBuilderService builderService;
    private GraphSearchService  searchService;
    private GraphTraversalService traversalService;

    @BeforeEach
    void setUp() {
        builderService   = new GraphBuilderService();
        searchService    = new GraphSearchService();
        traversalService = new GraphTraversalService();
    }

    @AfterEach
    void tearDown() throws IOException {
        searchService.close();
    }

    @Test
    void buildGraphFromMarkdownFiles() throws IOException {
        writeNote("spring.md", """
                # Spring Framework
                Spring Boot makes it easy to create stand-alone applications.
                See also [[kafka.md]] and [[redis.md]].
                #backend #java
                """);

        writeNote("kafka.md", """
                # Apache Kafka
                Kafka is a distributed streaming platform.
                Works well with #backend systems.
                """);

        writeNote("redis.md", """
                # Redis
                Redis is an in-memory data store. Useful for caching in #backend apps.
                See [[spring.md]] for integration tips.
                """);

        KnowledgeGraph graph = builderService.build(notesDir);

        assertEquals(3, graph.size(), "should have 3 nodes");
        assertFalse(graph.getEdges().isEmpty(), "should have edges");

        // spring → kafka and spring → redis via wiki links
        boolean springKafka = graph.getEdges().stream()
                .anyMatch(e -> (e.source().equals("spring.md") && e.target().equals("kafka.md"))
                            || (e.source().equals("kafka.md") && e.target().equals("spring.md")));
        assertTrue(springKafka, "spring ↔ kafka edge expected (wiki link)");
    }

    @Test
    void wikiLinkBoostsMakeHighestWeight() throws IOException {
        writeNote("a.md", "# A\n[[b.md]] #tag1\n");
        writeNote("b.md", "# B\n#tag1\n");
        writeNote("c.md", "# C\n#tag1\n");

        KnowledgeGraph graph = builderService.build(notesDir);

        // a↔b should have weight >= 5+2=7 (wiki_link + shared_tag)
        // b↔c should have weight 2 (shared_tag only)
        var abEdge = graph.getEdges().stream()
                .filter(e -> (e.source().equals("a.md") && e.target().equals("b.md"))
                          || (e.source().equals("b.md") && e.target().equals("a.md")))
                .findFirst();
        assertTrue(abEdge.isPresent(), "a↔b edge expected");
        assertTrue(abEdge.get().weight() >= 7, "wiki_link + shared_tag weight expected");
    }

    @Test
    void luceneSearchFindsRelevantNotes() throws IOException {
        writeNote("spring.md",  "# Spring\nSpring Boot auto-configuration.\n#java\n");
        writeNote("kafka.md",   "# Kafka\nKafka streams for event streaming.\n#java\n");
        writeNote("postgres.md","# PostgreSQL\nRelational database management system.\n#database\n");

        KnowledgeGraph graph = builderService.build(notesDir);
        searchService.index(graph.getAllMdFiles());

        List<String> results = searchService.search("Spring Boot", 5);
        assertFalse(results.isEmpty(), "Lucene should find spring.md");
        assertEquals("spring.md", results.get(0));
    }

    @Test
    void graphTraversalReturnsNeighbors() throws IOException {
        writeNote("spring.md", "# Spring\n[[kafka.md]]\n[[redis.md]]\n#backend\n");
        writeNote("kafka.md",  "# Kafka\n#backend\n");
        writeNote("redis.md",  "# Redis\n#backend\n");
        writeNote("other.md",  "# Other\n#frontend\n");

        KnowledgeGraph graph = builderService.build(notesDir);
        List<String> neighbors = traversalService.traverse(graph, "spring.md", 1, 5, 1);

        assertTrue(neighbors.contains("kafka.md"), "kafka should be a neighbor");
        assertTrue(neighbors.contains("redis.md"), "redis should be a neighbor");
        assertFalse(neighbors.contains("spring.md"), "startNote excluded");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeNote(String name, String content) throws IOException {
        Files.writeString(notesDir.resolve(name), content);
    }
}
