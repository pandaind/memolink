package ist.logic.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches a directory tree for Markdown file changes and triggers an
 * incremental graph rebuild after a short debounce window.
 *
 * <ul>
 *   <li><b>Recursive</b> — registers all existing sub-directories on start
 *       and auto-registers newly created ones at runtime.</li>
 *   <li><b>Debounced</b> — rapid consecutive saves (e.g. editor auto-save)
 *       produce only one rebuild, fired {@value #DEBOUNCE_MS} ms after the
 *       last detected change.</li>
 *   <li><b>Incremental</b> — passes the set of changed paths to the callback
 *       so callers can use {@link GraphBuilderService#buildIncremental} instead
 *       of a full rescan.</li>
 *   <li><b>Framework-agnostic</b> — plain Java, no Spring dependency.
 *       Spring starters manage its lifecycle via {@code destroyMethod="close"}
 *       on the {@code @Bean} declaration.</li>
 * </ul>
 *
 * <p>The {@code onChanged} callback receives the set of changed {@link Path}s.
 * Callers are responsible for rebuilding the {@link KnowledgeGraph} and
 * re-indexing {@link GraphSearchService} inside the callback.
 */
public class GraphWatchService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(GraphWatchService.class);
    private static final long   DEBOUNCE_MS = 500;

    private final Path                     rootDir;
    private final Consumer<Set<Path>>      onChanged;

    private final WatchService             watchService;
    private final ScheduledExecutorService debouncer;
    private final ExecutorService          watchThread;

    /** Paths of changed .md files accumulated during the current debounce window. */
    private final Set<Path>                          pendingPaths = ConcurrentHashMap.newKeySet();
    private final AtomicReference<ScheduledFuture<?>> pending     = new AtomicReference<>();

    public GraphWatchService(Path rootDir, Consumer<Set<Path>> onChanged) throws IOException {
        this.rootDir   = rootDir;
        this.onChanged = onChanged;

        this.watchService = FileSystems.getDefault().newWatchService();
        this.debouncer    = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memolink-debouncer");
            t.setDaemon(true);
            return t;
        });
        this.watchThread  = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memolink-watcher");
            t.setDaemon(true);
            return t;
        });

        registerAll(rootDir);
        watchThread.submit(this::watchLoop);
        log.info("Watching for markdown changes in: {}", rootDir);
    }

    // ── Directory registration ────────────────────────────────────────────────

    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── Watch loop ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            for (WatchEvent<?> rawEvent : key.pollEvents()) {
                WatchEvent.Kind<?> kind = rawEvent.kind();
                if (kind == OVERFLOW) continue;

                WatchEvent<Path> event   = (WatchEvent<Path>) rawEvent;
                Path             changed = ((Path) key.watchable()).resolve(event.context());

                // Auto-register newly created sub-directories
                if (kind == ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerAll(changed);
                    } catch (IOException ignored) {}
                }

                if (changed.toString().endsWith(".md")) {
                    pendingPaths.add(changed);
                    scheduleRebuild();
                }
            }

            if (!key.reset()) {
                log.debug("Watch key invalidated (directory removed?): {}", key.watchable());
            }
        }
    }

    // ── Debounced rebuild ─────────────────────────────────────────────────────

    private void scheduleRebuild() {
        ScheduledFuture<?> old = pending.getAndSet(null);
        if (old != null) old.cancel(false);

        pending.set(debouncer.schedule(() -> {
            Set<Path> snapshot = new HashSet<>(pendingPaths);
            pendingPaths.removeAll(snapshot);
            log.info("Markdown change detected — {} file(s) changed", snapshot.size());
            onChanged.accept(snapshot);
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() throws IOException {
        debouncer.shutdownNow();
        watchThread.shutdownNow();
        watchService.close();
    }
}
