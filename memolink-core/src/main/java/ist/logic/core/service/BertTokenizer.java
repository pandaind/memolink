package ist.logic.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

/**
 * Minimal pure-Java BERT WordPiece tokenizer for sentence-transformers models.
 * Reads the vocabulary from a HuggingFace tokenizer.json file.
 * No native libraries required — works identically inside a Spring Boot fat jar.
 *
 * Implements:
 * - BertNormalizer  (lowercase, NFD + strip combining marks)
 * - BertPreTokenizer (whitespace + punctuation splitting, CJK char spacing)
 * - WordPiece greedy tokenization with "##" continuation prefix
 * - TemplateProcessing: [CLS] {ids} [SEP]
 */
public final class BertTokenizer implements Closeable {

    private static final int CLS_ID           = 101;
    private static final int SEP_ID           = 102;
    private static final int UNK_ID           = 100;
    private static final int PAD_ID           = 0;
    private static final int MAX_CHARS        = 100;   // max chars per word for WordPiece
    private static final String CONT_PREFIX   = "##";

    private final Map<String, Integer> vocab;

    public BertTokenizer(Path tokenizerJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(tokenizerJson.toFile());
        JsonNode vocabNode = root.path("model").path("vocab");
        vocab = new HashMap<>(vocabNode.size() * 2);
        vocabNode.fields().forEachRemaining(e -> vocab.put(e.getKey(), e.getValue().asInt()));
    }

    /**
     * Encodes {@code text} and returns three arrays of length {@code maxLen}:
     * {@code [input_ids, attention_mask, token_type_ids]}.
     */
    public long[][] encode(String text, int maxLen) {
        List<Integer> ids = tokenize(text);

        // Truncate to maxLen-2 to leave room for [CLS] and [SEP]
        int contentMax = maxLen - 2;
        if (ids.size() > contentMax) ids = ids.subList(0, contentMax);

        long[] inputIds      = new long[maxLen];
        long[] attentionMask = new long[maxLen];
        long[] tokenTypeIds  = new long[maxLen];   // all zeros (single-sequence)

        inputIds[0]      = CLS_ID;
        attentionMask[0] = 1L;
        for (int i = 0; i < ids.size(); i++) {
            inputIds[i + 1]      = ids.get(i);
            attentionMask[i + 1] = 1L;
        }
        inputIds[ids.size() + 1]      = SEP_ID;
        attentionMask[ids.size() + 1] = 1L;
        // positions beyond SEP stay 0 (PAD)

        return new long[][]{ inputIds, attentionMask, tokenTypeIds };
    }

    /**
     * Encodes a sentence pair for cross-encoder models.
     * Produces: {@code [CLS] queryTokens [SEP] passageTokens [SEP]}
     * with {@code token_type_ids} = 0 for query segment, 1 for passage segment.
     *
     * @param query   the search query
     * @param passage the candidate passage to score against the query
     * @param maxLen  total sequence length including special tokens
     * @return {@code [input_ids, attention_mask, token_type_ids]}
     */
    public long[][] encodePair(String query, String passage, int maxLen) {
        List<Integer> queryIds   = tokenize(query   == null ? "" : query);
        List<Integer> passageIds = tokenize(passage == null ? "" : passage);

        // Budget: [CLS] + queryIds + [SEP] + passageIds + [SEP]
        // Reserve 3 positions for the special tokens
        int budget = maxLen - 3;
        // Allocate up to half the budget to query, rest to passage
        int qMax = Math.min(queryIds.size(),   budget / 2);
        int pMax = Math.min(passageIds.size(), budget - qMax);
        queryIds   = queryIds.subList(0, qMax);
        passageIds = passageIds.subList(0, pMax);

        long[] inputIds      = new long[maxLen];
        long[] attentionMask = new long[maxLen];
        long[] tokenTypeIds  = new long[maxLen];

        int pos = 0;

        // [CLS] — token type 0
        inputIds[pos] = CLS_ID;  attentionMask[pos] = 1L;  pos++;

        // Query tokens — token type 0
        for (int id : queryIds) {
            inputIds[pos] = id;  attentionMask[pos] = 1L;  pos++;
        }

        // [SEP] — token type 0
        inputIds[pos] = SEP_ID;  attentionMask[pos] = 1L;  pos++;

        // Passage tokens — token type 1
        for (int id : passageIds) {
            inputIds[pos]     = id;
            attentionMask[pos] = 1L;
            tokenTypeIds[pos]  = 1L;
            pos++;
        }

        // [SEP] — token type 1
        inputIds[pos]     = SEP_ID;
        attentionMask[pos] = 1L;
        tokenTypeIds[pos]  = 1L;
        // remaining positions stay PAD / 0

        return new long[][]{ inputIds, attentionMask, tokenTypeIds };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Integer> tokenize(String text) {
        String normalised = stripAccents(text.toLowerCase(Locale.ROOT));
        List<String> words = bertPreTokenize(normalised);
        List<Integer> ids = new ArrayList<>(words.size() * 2);
        for (String word : words) wordpieceTokenize(word, ids);
        return ids;
    }

    private static String stripAccents(String text) {
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (char c : nfd.toCharArray()) {
            if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString();
    }

    private static List<String> bertPreTokenize(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjkChar(c) || isPunctuation(c)) {
                sb.append(' ').append(c).append(' ');
            } else if (Character.isWhitespace(c)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) return Collections.emptyList();
        return Arrays.asList(s.split(" +"));
    }

    private void wordpieceTokenize(String word, List<Integer> out) {
        if (word.length() > MAX_CHARS) { out.add(UNK_ID); return; }
        List<Integer> subIds = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String sub = (start == 0 ? "" : CONT_PREFIX) + word.substring(start, end);
                Integer id = vocab.get(sub);
                if (id != null) { found = id; break; }
                end--;
            }
            if (found == null) { out.add(UNK_ID); return; }
            subIds.add(found);
            start = end;
        }
        out.addAll(subIds);
    }

    private static boolean isCjkChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   || (c >= 0x3400 && c <= 0x4DBF)
            || (c >= 0xF900 && c <= 0xFAFF)   || (c >= 0x2F800 && c <= 0x2FA1F);
    }

    private static boolean isPunctuation(char c) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return false;
        int type = Character.getType(c);
        return type == Character.DASH_PUNCTUATION
            || type == Character.START_PUNCTUATION
            || type == Character.END_PUNCTUATION
            || type == Character.CONNECTOR_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION
            || type == Character.MATH_SYMBOL
            || type == Character.CURRENCY_SYMBOL
            || type == Character.MODIFIER_SYMBOL
            || type == Character.OTHER_SYMBOL
            || (c >= 33 && c <= 47)   // !"#$%&'()*+,-./
            || (c >= 58 && c <= 64)   // :;<=>?@
            || (c >= 91 && c <= 96)   // [\]^_`
            || (c >= 123 && c <= 126);// {|}~
    }

    @Override
    public void close() { /* nothing to release */ }
}
