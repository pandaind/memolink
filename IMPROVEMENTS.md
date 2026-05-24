# MemoLink — Performance & Quality Improvements

_Implemented 2026-05-24_

---

## 1. Resource Leak Fix — Old `GraphSearchService` Closed on Swap

**File:** `memolink-core/.../service/GraphHolder.java`

**Problem:** Every time a file change triggered a graph rebuild, a new `GraphSearchService` was swapped in atomically via `volatile Snapshot`. The old Lucene `ByteBuffersDirectory` and `DirectoryReader` were never closed, leaking memory indefinitely in long-running processes.

**Fix:** `GraphHolder.update()` now saves the old snapshot before the swap, performs the atomic write, then calls `old.searchService().close()` (with a `log.warn` on failure).

```java
// before
this.snapshot = new Snapshot(graph, searchService);

// after
Snapshot old = this.snapshot;
this.snapshot = new Snapshot(graph, searchService);
try { old.searchService().close(); } catch (Exception e) { log.warn(...); }
```

---

## 2. Phrase Search Fix — Stop Over-Escaping Lucene Queries

**File:** `memolink-core/.../service/GraphSearchService.java`

**Problem:** Every search query was passed through `MultiFieldQueryParser.escape()` before parsing. This turned `"spring boot config"` into `spring\ boot\ config`, breaking phrase search and proximity matching.

**Fix:** New `buildQuery()` helper tries the raw query first (preserving phrase intent). Only if the raw parse throws `ParseException` does it fall back to the escaped form. A third fallback returns an empty `BooleanQuery` rather than crashing.

Default operator set to `AND` so multi-word queries require all terms to match.

```
Query resolution order:
  1. raw query          → phrase and proximity preserved
  2. escaped query      → handles stray Lucene metacharacters
  3. empty BooleanQuery → never throws to the caller
```

---

## 3. Structured Note Content — `NoteDetail` Record

**Files:** `memolink-core/.../model/NoteDetail.java` _(new)_, both tool files

**Problem:** `getMdFileContent` / `get_md_file` returned raw markdown text. LLMs received noisy syntax tokens (`#`, `[[`, `##`) mixed with prose, wasting context tokens and confusing structured extraction.

**Fix:** New `NoteDetail` record:

```java
record NoteDetail(String id, String title, List<String> tags,
                  List<String> headings, List<String> wikiLinks, String body)
```

`NoteDetail.from(MdFileMetadata)` strips the H1 line and tag-only lines, leaving clean prose in `body`. The LLM receives structured, pre-parsed data in every field.

---

## 4. Search Results Include Score and Title — `SearchResult` Record

**Files:** `memolink-core/.../model/SearchResult.java` _(new)_, both tool files

**Problem:** Search tools returned `List<String>` (note IDs only). Agents had no way to judge relevance without fetching every result's full content.

**Fix:** New `SearchResult` record:

```java
record SearchResult(String id, String title, float score)
```

Both `searchMdFiles` and `search_md_files` now return `List<SearchResult>`. An agent can read scores and titles, then selectively call the content tool only for high-confidence hits — reducing unnecessary round trips.

---

## 5. Incremental Rebuild on File Changes

**Files:** `memolink-core/.../service/GraphWatchService.java`, `GraphBuilderService.java`, three Spring config files

**Problem:** Any single markdown file change triggered a full re-scan and re-parse of every note in the vault, regardless of size. A 1,000-note vault did 1,000 disk reads for a one-line edit.

**Fix — `GraphWatchService`:**
- Callback type changed from `Consumer<KnowledgeGraph>` to `Consumer<Set<Path>>`.
- Each changed `.md` path is added to a `ConcurrentHashMap.newKeySet()` accumulator during the debounce window.
- `AtomicReference<ScheduledFuture<?>>` replaces `synchronized` for the pending-future swap (lock-free).
- When the debounce timer fires, a snapshot of the accumulated paths is forwarded to the callback.

**Fix — `GraphBuilderService.buildIncremental()`:**
```java
// Copies existing notes into a mutable map, then:
//   - re-parses files that changed or were created
//   - removes entries for deleted files
// Re-scores edges over the resulting full note set.
```
Only changed files touch the disk; unchanged notes are carried over from the live `KnowledgeGraph`.

**Fix — Spring configs:**
All three auto-configuration classes (`MemoLinkViewerAutoConfiguration`, `MemoLinkAiAutoConfiguration`, `MemoLinkMcpConfig`) updated to use the new incremental callback pattern.

---

## 6. O(n²) → Inverted-Index Edge Building in `RelationshipEngine`

**File:** `memolink-core/.../service/RelationshipEngine.java`

**Problem:** Edge scoring iterated over every pair `(i, j)` with `i < j`, comparing tags, keywords, and wiki-links for every combination. Cost grew quadratically — 1,000 notes meant ~500,000 pair comparisons on every rebuild.

**Fix:** Build inverted indexes before scoring:

```
tag     → [noteId, noteId, ...]
keyword → [noteId, noteId, ...]
```

Then, for each bucket, accumulate score for all pairs within the bucket. Only pairs that share at least one signal ever get a score entry. Pairs with nothing in common are never visited.

Complexity reduces from **O(n²)** to **O(n·k)** where _k_ is the average token fan-out (typically small and bounded).

Scoring rules preserved:

| Signal           | Weight | Cap per pair |
|------------------|--------|-------------|
| `wiki_link`      | +5     | 1            |
| `shared_tags`    | +2     | 3 tags       |
| `shared_keywords`| +1     | 5 keywords   |

---

## 7. Contribution Cap Fix — `RelationshipEngine.accumulatePairs()`

**File:** `memolink-core/.../service/RelationshipEngine.java`

**Problem:** The `maxContributions` cap (max 3 shared tags, max 5 shared keywords per pair) was silently broken. `pairReasons` stored each reason string in a `Set<String>`, so `reasons.stream().filter(r -> r.equals(reason)).count()` always returned 0 (first occurrence) or 1 (every subsequent one). Since 1 < 3 (or 5) always held, the cap was never enforced after the first match — a pair sharing 10 tags accumulated weight for all 10 instead of capping at 3.

**Root cause in one line:**
```java
// Set can contain at most one copy of a string, so existing is always 0 or 1:
long existing = reasons.stream().filter(r -> r.equals(reason)).count();
if (existing < maxContributions) { ... reasons.add(reason); } // add is a no-op after first
```

**Fix:** Replace `Map<String, Set<String>> pairReasons` with `Map<String, Map<String, Integer>> pairReasonCount` — an integer counter per (pair, reason) key. The check and update become O(1) hash lookups with no `Stream` allocation:

```java
// before
long existing = reasons.stream().filter(r -> r.equals(reason)).count();
if (existing < maxContributions) {
    score[0] += weight;
    reasons.add(reason);          // no-op after first add — cap broken
}

// after
int existing = reasonCount.getOrDefault(reason, 0);
if (existing < maxContributions) {
    score[0] += weight;
    reasonCount.put(reason, existing + 1);  // exact count, cap enforced
}
```

Edge weights now respect the documented caps:
- `shared_tags` → max contribution `+2 × 3 = +6`
- `shared_keywords` → max contribution `+1 × 5 = +5`

Also removes the `Stream` and lambda allocation on every inner-loop iteration.

---

## Build Verification

All seven changes build cleanly:

```
mvn clean install -DskipTests  →  BUILD SUCCESS
```
