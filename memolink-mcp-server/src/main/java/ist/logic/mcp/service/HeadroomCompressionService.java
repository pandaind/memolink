package ist.logic.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ist.logic.mcp.config.HeadroomProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the headroom kompress-small ONNX sidecar ({@code POST /compress}) to
 * reduce the token count of large text before it is returned to the LLM.
 *
 * <p>Compression is <em>best-effort</em>: if the sidecar is unavailable, the
 * original content is returned unchanged.  This keeps the MCP server
 * operational even when headroom is not running.
 *
 * <p>Configured via {@link HeadroomProperties}:
 * <pre>
 * memolink.headroom.url=http://headroom:8787
 * memolink.headroom.enabled=true
 * memolink.headroom.min-chars=300
 * </pre>
 */
@Service
public class HeadroomCompressionService {

    private static final Logger log = LoggerFactory.getLogger(HeadroomCompressionService.class);

    private final HeadroomProperties properties;
    private final HttpClient          httpClient;
    private final ObjectMapper        mapper;

    public HeadroomCompressionService(HeadroomProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)  // uvicorn does not support h2c upgrades
                .build();
        this.mapper = new ObjectMapper();

        if (properties.isActive()) {
            log.info("Headroom compression enabled — sidecar: {}", properties.getUrl());
        } else {
            log.info("Headroom compression disabled (set memolink.headroom.enabled=true to enable)");
        }
    }

    /**
     * Returns whether compression will be attempted for a given content string.
     * Short content is always skipped for performance.
     */
    public boolean willCompress(String content) {
        return properties.isActive()
                && content != null
                && content.length() >= properties.getMinChars();
    }

    /**
     * Compresses {@code content} using the headroom sidecar.
     *
     * <ul>
     *   <li>Returns the original {@code content} unchanged on any error.</li>
     *   <li>Returns the original {@code content} if it is shorter than
     *       {@code memolink.headroom.min-chars} (avoids unnecessary HTTP
     *       round-trips for small notes).</li>
     * </ul>
     *
     * @param content text to compress (note body, excerpt, etc.)
     * @return compressed text, or original on failure / short content
     */
    public String compress(String content) {
        if (!willCompress(content)) {
            return content;
        }

        try {
            String requestJson = mapper.writeValueAsString(Map.of(
                    "content",    content,
                    "min_length", properties.getMinChars()
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl() + "/compress"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Headroom returned HTTP {}; using original content", response.statusCode());
                return content;
            }

            JsonNode body = mapper.readTree(response.body());

            // Print compression stats to stderr to keep MCP stdout clean but visible in logs
            double ratio = body.path("compression_ratio").asDouble(1.0);
            int original = body.path("original_tokens").asInt(0);
            int compressed = body.path("compressed_tokens").asInt(0);
            int saved = original - compressed;
            System.err.printf("[Headroom] Compressed text: tokens %d -> %d (saved %d), ratio=%.2f%n", 
                    original, compressed, saved, ratio);

            return body.path("compressed").asText(content);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Headroom call interrupted; using original content");
            return content;
        } catch (Exception e) {
            log.debug("Headroom call failed ({}); using original content", e.getMessage());
            return content;
        }
    }
}
