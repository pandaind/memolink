package ist.logic.mcp.controller;

import ist.logic.mcp.service.StopWordFilterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/filter-stopwords")
@ConditionalOnWebApplication
public class StopWordController {

    private final StopWordFilterService filterService;

    public StopWordController(StopWordFilterService filterService) {
        this.filterService = filterService;
    }

    @PostMapping
    public Map<String, Object> filterParagraph(@RequestBody Map<String, String> request) {
        String paragraph = request.getOrDefault("text", "");
        long startTime = System.currentTimeMillis();
        String filtered = filterService.strip(paragraph);
        long endTime = System.currentTimeMillis();

        return Map.of(
                "originalLength", paragraph.length(),
                "filteredLength", filtered.length(),
                "originalWords", paragraph.split("\\s+").length,
                "filteredWords", filtered.split("\\s+").length,
                "processingTimeMs", (endTime - startTime),
                "filteredText", filtered
        );
    }
}
