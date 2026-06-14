package ist.logic.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class NativeHandoffService {

    private static final Logger log = LoggerFactory.getLogger(NativeHandoffService.class);

    @Value("${memolink.mcp.native-handoff.enabled:false}")
    private boolean enabled;

    @Value("${memolink.mcp.native-handoff.dir:${user.home}/vault/.cache}")
    private String cacheDir;

    @Value("${memolink.mcp.native-handoff.host-dir:}")
    private String hostDir;

    /**
     * Intercepts large text content and writes it to a local cache file if Native Handoff is enabled.
     * Returns a pointer to the file for the IDE to read, bypassing MCP token tracking.
     *
     * @param toolName The name of the tool generating the content (e.g. "get_md_file")
     * @param identifier An identifier for the content (e.g. the file_id)
     * @param content The actual large text content
     * @return Either the original content, or a handoff message pointing to the saved file.
     */
    public String handoff(String toolName, String identifier, String content) {
        if (!enabled || content == null || content.isBlank()) {
            return content;
        }

        try {
            Path dir = Paths.get(cacheDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // Clean the identifier to be safe for filenames
            String safeId = identifier.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = String.format("%s_%s_%s.md", toolName, safeId, UUID.randomUUID().toString().substring(0, 8));
            Path targetFile = dir.resolve(filename);

            Files.writeString(targetFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // If running in Docker, we must return the absolute path on the HOST machine, not the container!
            String reportedPath = targetFile.toAbsolutePath().toString();
            if (hostDir != null && !hostDir.isBlank()) {
                reportedPath = Paths.get(hostDir).resolve(filename).toAbsolutePath().toString();
            }

            return String.format(
                    "[NATIVE HANDOFF]\n" +
                    "Content is too large for chat history and has been saved to disk to prevent LLM context-window bleed.\n\n" +
                    "Please use your IDE's native file-reading tool (e.g., `view_file` or `@file` attachment) to read this file directly:\n" +
                    "%s",
                    reportedPath
            );

        } catch (IOException e) {
            log.error("Failed to write Native Handoff cache file: {}", e.getMessage());
            // Fall back to returning the full content if file write fails
            return content;
        }
    }
}
