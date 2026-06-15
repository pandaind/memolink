package ist.logic.mcp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class StopWordFilterService {

    @Value("${memolink.stop-words.enabled:false}")
    private boolean enabled;

    @Value("#{'${memolink.stop-words.words:a,an,and,are,as,at,be,but,by,for,if,in,into,is,it,no,not,of,on,or,such,that,the,their,then,there,these,they,this,to,was,will,with}'.split(',')}")
    private Set<String> stopWords;

    /**
     * Strips stop words from the provided text if the filter is enabled.
     * Retains formatting roughly but removes purely structural/grammatical words.
     */
    public String strip(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        for (String stopWord : stopWords) {
            // Regex to match the stop word as a whole word, case-insensitive
            // \\b is word boundary. (?i) is case-insensitive.
            result = result.replaceAll("(?i)\\b" + stopWord + "\\b", "");
        }
        // Collapse multiple spaces (but NOT newlines) created by removing words into a single space
        return result.replaceAll("[ \\t]{2,}", " ").trim();
    }
}
