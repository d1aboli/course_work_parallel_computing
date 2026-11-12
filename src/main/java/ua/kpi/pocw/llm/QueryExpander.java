package ua.kpi.pocw.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ua.kpi.pocw.core.Tokenizer;
import ua.kpi.pocw.util.Json;

/**
 * Query expansion through a local LLM with caching, request coalescing and bounded concurrency.
 *
 * <ul>
 *   <li><b>LRU cache</b> of recent expansions ({@link LinkedHashMap} in access order under a lock);
 *   <li><b>single-flight</b>: concurrent requests for the same query share one model call
 *       ({@link ConcurrentHashMap#computeIfAbsent} of a {@link CompletableFuture});
 *   <li><b>bounded concurrency</b>: at most {@code maxConcurrent} calls reach Ollama at once
 *       ({@link Semaphore}); the model itself is the serial part of the pipeline;
 *   <li><b>deadline</b>: the caller never waits longer than {@code timeout} in total; on timeout or
 *       any model error the expansion is empty and the search goes on with the original terms.
 * </ul>
 */
public final class QueryExpander {
  /** Result of an expansion; {@code source} is llm, cache, empty, timeout, error or disabled. */
  public record Expansion(List<String> terms, String source, long timeMs) {
    public boolean degraded() {
      return source.equals("timeout") || source.equals("error") || source.equals("disabled");
    }
  }

  static final int MAX_TERMS = 8;
  private static final Pattern QUOTED = Pattern.compile("\"([A-Za-z][A-Za-z0-9 \\-']{1,40})\"");

  private final OllamaClient client;
  private final Duration timeout;
  private final Semaphore permits;
  private final int cacheCapacity;
  private final ReentrantLock cacheLock = new ReentrantLock();
  private final LinkedHashMap<String, List<String>> cache;
  private final ConcurrentHashMap<String, CompletableFuture<List<String>>> inFlight =
      new ConcurrentHashMap<>();

  public QueryExpander(OllamaClient client, Duration timeout, int maxConcurrent, int cacheSize) {
    this.client = client;
    this.timeout = timeout;
    this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
    this.cacheCapacity = cacheSize;
    this.cache = new LinkedHashMap<>(16, 0.75f, true);
  }

  public Expansion expand(String query, List<String> queryTerms) {
    return expand(query, queryTerms, true);
  }

  /** Expands a query; {@code useCache=false} forces a model call (used by benchmarks). */
  public Expansion expand(String query, List<String> queryTerms, boolean useCache) {
    long t0 = System.nanoTime();
    if (client == null) {
      return new Expansion(List.of(), "disabled", 0);
    }
    if (queryTerms.isEmpty()) {
      return new Expansion(List.of(), "empty", 0);
    }
    String key = String.join(" ", queryTerms);
    List<String> cached = useCache ? cacheGet(key) : null;
    if (cached != null) {
      return new Expansion(cached, "cache", elapsed(t0));
    }
    CompletableFuture<List<String>> mine = new CompletableFuture<>();
    CompletableFuture<List<String>> shared = useCache ? inFlight.putIfAbsent(key, mine) : null;
    if (shared == null) {
      try {
        mine.complete(callModel(query, queryTerms, t0));
      } catch (Exception e) {
        mine.completeExceptionally(e);
      } finally {
        if (useCache) {
          inFlight.remove(key, mine);
        }
      }
      shared = mine;
    }
    try {
      long left = timeout.toNanos() - (System.nanoTime() - t0);
      List<String> terms = shared.get(Math.max(1, left), TimeUnit.NANOSECONDS);
      cachePut(key, terms);
      return new Expansion(terms, "llm", elapsed(t0));
    } catch (TimeoutException e) {
      return new Expansion(List.of(), "timeout", elapsed(t0));
    } catch (ExecutionException e) {
      Throwable c = e.getCause();
      String src = c instanceof TimeoutException || c instanceof java.net.http.HttpTimeoutException
          ? "timeout" : "error";
      System.err.println("[expander] " + src + ": " + c);
      return new Expansion(List.of(), src, elapsed(t0));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Expansion(List.of(), "error", elapsed(t0));
    }
  }

  private List<String> callModel(String query, List<String> queryTerms, long t0)
      throws Exception {
    long left = timeout.toNanos() - (System.nanoTime() - t0);
    if (!permits.tryAcquire(Math.max(0, left), TimeUnit.NANOSECONDS)) {
      throw new TimeoutException("waiting for an LLM slot");
    }
    try {
      left = timeout.toNanos() - (System.nanoTime() - t0);
      if (left <= 0) {
        throw new TimeoutException("deadline passed");
      }
      String raw = client.generate(prompt(query), Duration.ofNanos(left));
      return clean(parseTerms(raw), queryTerms);
    } finally {
      permits.release();
    }
  }

  static String prompt(String query) {
    return "You expand search queries for a full-text search engine over English books.\n"
        + "Return ONLY a JSON object {\"terms\": [...]} with 5 to 8 single English words:\n"
        + "synonyms or closely related keywords for the query. Lowercase, one word each,\n"
        + "no phrases, no repeats, and none of the query words themselves.\n"
        + "Example: query \"ocean storm\" -> {\"terms\": [\"sea\", \"tempest\", \"waves\","
        + " \"gale\", \"hurricane\", \"sailors\"]}\n"
        + "Query: \""
        + query.replace('"', ' ').replace('\n', ' ')
        + "\"";
  }

  /** Extracts candidate terms from a model answer that may be malformed or truncated. */
  static List<String> parseTerms(String raw) {
    String text = raw.strip();
    if (text.startsWith("```")) {
      text = text.replaceAll("^```[a-zA-Z]*", "").replaceAll("```\\s*$", "").strip();
    }
    List<String> out = new ArrayList<>();
    int brace = text.indexOf('{');
    int bracket = text.indexOf('[');
    try {
      Object parsed = null;
      if (brace >= 0 && text.lastIndexOf('}') > brace) {
        parsed = Json.parse(text.substring(brace, text.lastIndexOf('}') + 1));
      } else if (bracket >= 0 && text.lastIndexOf(']') > bracket) {
        parsed = Json.parse(text.substring(bracket, text.lastIndexOf(']') + 1));
      }
      collectStrings(parsed, out);
    } catch (Json.ParseException ignored) {
      // fall through to the tolerant regex below
    }
    if (out.isEmpty()) {
      Matcher m = QUOTED.matcher(text);
      while (m.find()) {
        String s = m.group(1);
        if (!s.equalsIgnoreCase("terms") && !s.equalsIgnoreCase("keywords")) {
          out.add(s);
        }
      }
    }
    return out;
  }

  private static void collectStrings(Object node, List<String> out) {
    if (node instanceof String s) {
      out.add(s);
    } else if (node instanceof List<?> list) {
      list.forEach(x -> collectStrings(x, out));
    } else if (node instanceof Map<?, ?> map) {
      Object preferred = map.containsKey("terms") ? map.get("terms") : map.get("keywords");
      if (preferred != null) {
        collectStrings(preferred, out);
      } else {
        map.values().forEach(v -> collectStrings(v, out));
      }
    }
  }

  /** Tokenizes candidates like the index does, drops stop words, duplicates and query terms. */
  static List<String> clean(List<String> candidates, List<String> queryTerms) {
    Set<String> query = Set.copyOf(queryTerms);
    Set<String> out = new LinkedHashSet<>();
    for (String c : candidates) {
      for (String t : Tokenizer.tokenizeQuery(c)) {
        if (!query.contains(t) && !t.chars().allMatch(Character::isDigit)) {
          out.add(t);
        }
      }
      if (out.size() >= MAX_TERMS) {
        break;
      }
    }
    return new ArrayList<>(out).subList(0, Math.min(MAX_TERMS, out.size()));
  }

  private List<String> cacheGet(String key) {
    cacheLock.lock();
    try {
      return cache.get(key);
    } finally {
      cacheLock.unlock();
    }
  }

  private void cachePut(String key, List<String> terms) {
    cacheLock.lock();
    try {
      cache.put(key, List.copyOf(terms));
      if (cache.size() > cacheCapacity) {
        cache.remove(cache.keySet().iterator().next());
      }
    } finally {
      cacheLock.unlock();
    }
  }

  private static long elapsed(long t0) {
    return (System.nanoTime() - t0) / 1_000_000;
  }

  public int cacheSize() {
    cacheLock.lock();
    try {
      return cache.size();
    } finally {
      cacheLock.unlock();
    }
  }

  /** Clears the cache (used by benchmarks that measure uncached model latency). */
  public void clearCache() {
    cacheLock.lock();
    try {
      cache.clear();
    } finally {
      cacheLock.unlock();
    }
  }
}
