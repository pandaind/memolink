package ist.logic.core.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Recursively scans a directory for {@code .md} files.
 */
public class MdFileScannerService {

    public List<Path> scan(Path rootDir) throws IOException {
        if (!Files.isDirectory(rootDir)) {
            throw new IllegalArgumentException("Not a directory: " + rootDir);
        }
        try (var stream = Files.walk(rootDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }
}
