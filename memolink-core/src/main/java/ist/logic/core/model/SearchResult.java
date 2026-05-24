package ist.logic.core.model;

/**
 * A single search hit returned by {@link ist.logic.core.service.GraphSearchService}.
 *
 * @param id      file ID, e.g. {@code "spring-boot.md"}
 * @param title   note title (first H1 or filename stem)
 * @param score   Lucene relevance score — higher is more relevant
 */
public record SearchResult(String id, String title, float score) {}
