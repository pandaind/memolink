package ist.logic.core.service;

import ist.logic.core.model.GraphEdge;
import ist.logic.core.model.KnowledgeGraph;
import ist.logic.core.model.MdFileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the full pipeline:
 *   scan → parse → score relationships → build KnowledgeGraph
 *
 * <p>Use {@link #build(Path)} for the initial load and
 * {@link #buildIncremental(KnowledgeGraph, Set)} for subsequent updates.
 * Incremental mode re-parses only the changed files, avoiding a full vault scan.
 */
public class GraphBuilderService {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilderService.class);

    private final MdFileScannerService scanner = new MdFileScannerService();
    private final MdFileParserService  parser  = new MdFileParserService();
    private final RelationshipEngine   engine  = new RelationshipEngine();

    public KnowledgeGraph build(Path rootDir) throws IOException {
        log.info("Scanning md files in: {}", rootDir.toAbsolutePath());
        List<Path> paths = scanner.scan(rootDir);
        log.info("Found {} md files", paths.size());

        List<MdFileMetadata> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            try {
                files.add(parser.parse(path));
            } catch (IOException e) {
                log.warn("Skipping unreadable file {}: {}", path, e.getMessage());
            }
        }

        List<GraphEdge> edges = engine.buildEdges(files);
        log.info("Built graph: {} nodes, {} edges", files.size(), edges.size());

        return new KnowledgeGraph(files, edges);
    }

    /**
     * Rebuild the graph by re-parsing only {@code changedPaths}.
     *
     * <ul>
     *   <li>If a changed path still exists on disk → re-parse and replace.</li>
     *   <li>If it no longer exists (deleted) → remove from the graph.</li>
     *   <li>All other notes are carried over from {@code current} as-is.</li>
     * </ul>
     *
     * Edge scoring is re-run over the full (updated) file list because any
     * change can affect relationships between other notes.
     */
    public KnowledgeGraph buildIncremental(KnowledgeGraph current, Set<Path> changedPaths) throws IOException {
        // Seed from current graph, keyed by file ID
        Map<String, MdFileMetadata> fileMap = new LinkedHashMap<>();
        for (MdFileMetadata m : current.getAllMdFiles()) {
            fileMap.put(m.getId(), m);
        }

        for (Path path : changedPaths) {
            String id = path.getFileName().toString();
            if (Files.exists(path)) {
                try {
                    MdFileMetadata updated = parser.parse(path);
                    fileMap.put(updated.getId(), updated);
                } catch (IOException e) {
                    log.warn("Skipping unreadable file {}: {}", path, e.getMessage());
                }
            } else {
                fileMap.remove(id);
            }
        }

        List<MdFileMetadata> files = new ArrayList<>(fileMap.values());
        List<GraphEdge> edges = engine.buildEdges(files);
        log.info("Incremental rebuild: {} nodes, {} edges ({} file(s) changed)",
                 files.size(), edges.size(), changedPaths.size());

        return new KnowledgeGraph(files, edges);
    }
}
