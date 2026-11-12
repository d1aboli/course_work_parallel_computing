package ua.kpi.pocw.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ua.kpi.pocw.core.Indexer;
import ua.kpi.pocw.llm.OllamaClient;
import ua.kpi.pocw.net.HttpException;
import ua.kpi.pocw.net.HttpRequest;
import ua.kpi.pocw.net.HttpResponse;
import ua.kpi.pocw.util.Json;

/**
 * REST API and static files. Implements the fixed contract of the assignment; additional fields
 * are only ever added, never renamed. Stateless apart from the components it delegates to, so it
 * is safe to call from all HTTP threads concurrently.
 */
public final class Router {
  private static final Set<String> SEARCH_FIELDS = Set.of("query", "mode", "expand", "top_k",
      "cache");
  private static final Set<String> INDEX_FIELDS = Set.of("threads", "partitions", "batch_size",
      "impl", "mapper", "gc");
  private static final long HEALTH_CACHE_MS = 2_000;

  private final Indexer indexer;
  private final SearchService search;
  private final OllamaClient ollama;
  private final Path webDir;
  private final int defaultPartitions;
  private final int defaultBatchSize;
  private final ServerMetrics metrics;

  private volatile boolean ollamaUp;
  private volatile long ollamaCheckedAt;

  /** Live counters provided by the server (decouples the router from the network layer). */
  public interface ServerMetrics {
    long requests();

    int openConnections();

    int httpThreads();
  }

  public Router(
      Indexer indexer,
      SearchService search,
      OllamaClient ollama,
      Path webDir,
      int defaultPartitions,
      int defaultBatchSize,
      ServerMetrics metrics) {
    this.indexer = indexer;
    this.search = search;
    this.ollama = ollama;
    this.webDir = webDir;
    this.defaultPartitions = defaultPartitions;
    this.defaultBatchSize = defaultBatchSize;
    this.metrics = metrics;
  }

  public HttpResponse handle(HttpRequest req) throws Exception {
    String path = req.path();
    return switch (path) {
      case "/api/health" -> only(req, "GET", () -> health());
      case "/api/index" -> only(req, "POST", () -> index(req));
      case "/api/search" -> only(req, "POST", () -> search(req));
      case "/api/stats" -> only(req, "GET", () -> stats());
      case "/ws/search" -> req.method().equals("GET")
          ? HttpResponse.error(426, "Use a WebSocket upgrade for /ws/search")
              .header("Upgrade", "websocket")
          : HttpResponse.error(405, "Method not allowed");
      default -> staticFile(req);
    };
  }

  @FunctionalInterface
  private interface Action {
    HttpResponse run() throws Exception;
  }

  private static HttpResponse only(HttpRequest req, String method, Action action)
      throws Exception {
    if (!req.method().equals(method)) {
      return HttpResponse.error(405, "Method not allowed, use " + method).header("Allow", method);
    }
    return action.run();
  }

  private HttpResponse health() {
    long now = System.currentTimeMillis();
    if (now - ollamaCheckedAt > HEALTH_CACHE_MS) {
      ollamaUp = ollama != null && ollama.isAvailable(Duration.ofSeconds(1));
      ollamaCheckedAt = now;
    }
    Indexer.Snapshot snap = indexer.snapshot();
    return HttpResponse.json(
        200,
        Json.obj(
            "status", "ok",
            "ollama", ollamaUp,
            "index_ready", snap != null,
            "indexing", indexer.isBuilding()));
  }

  private HttpResponse index(HttpRequest req) throws IOException, InterruptedException {
    Map<String, Object> body = req.body().length == 0 ? Map.of() : parseBody(req);
    warnUnknown(body, INDEX_FIELDS, "/api/index");
    int threads = intField(body, "threads", Runtime.getRuntime().availableProcessors());
    int partitions = intField(body, "partitions", defaultPartitions);
    int batch = intField(body, "batch_size", defaultBatchSize);
    String impl = stringField(body, "impl", "partitioned");
    if (!impl.equals("partitioned") && !impl.equals("striped")) {
      throw new HttpException(400, "impl must be 'partitioned' or 'striped'");
    }
    String mapper = stringField(body, "mapper", "arena");
    Indexer.BuildRequest br;
    try {
      br = new Indexer.BuildRequest(threads, partitions, batch, impl, mapper);
    } catch (IllegalArgumentException e) {
      throw new HttpException(400, e.getMessage());
    }
    Indexer.BuildStats s;
    try {
      s = indexer.build(br);
    } catch (Indexer.BuildInProgressException e) {
      throw new HttpException(409, e.getMessage());
    } catch (Indexer.EmptyCorpusException e) {
      throw new HttpException(409, e.getMessage());
    } catch (IOException e) {
      throw new HttpException(500, "Cannot read corpus: " + e.getMessage());
    }
    if (Boolean.TRUE.equals(body.get("gc"))) {
      System.gc(); // lets the benchmark measure the steady-state heap after the swap
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "ok");
    out.put("documents_indexed", s.documentsIndexed());
    out.put("time_ms", s.timeMs());
    out.put("threads", s.threads());
    out.put("partitions", s.partitions());
    out.put("batch_size", s.batchSize());
    out.put("tasks", s.tasks());
    out.put("impl", s.impl());
    out.put("mapper", s.mapper());
    out.put("documents_failed", s.documentsFailed());
    out.put("phases", s.phases());
    out.put("sync", s.sync());
    out.put("memory", Memory.snapshot());
    return HttpResponse.json(200, out);
  }

  private HttpResponse search(HttpRequest req) {
    Map<String, Object> body = parseBody(req);
    warnUnknown(body, SEARCH_FIELDS, "/api/search");
    Object q = body.get("query");
    if (!(q instanceof String query) || query.isBlank()) {
      throw new HttpException(400, "Field 'query' must be a non-empty string");
    }
    if (query.length() > 1000) {
      throw new HttpException(400, "Query is too long (max 1000 characters)");
    }
    String mode = stringField(body, "mode", "keyword");
    if (!mode.equalsIgnoreCase("keyword")) {
      throw new HttpException(400, "Unsupported mode '" + mode + "'; only 'keyword' is supported");
    }
    boolean expand = Boolean.TRUE.equals(body.get("expand"));
    boolean useCache = !Boolean.FALSE.equals(body.get("cache"));
    int topK = intField(body, "top_k", SearchService.DEFAULT_TOP_K);
    if (topK < 1 || topK > 100) {
      throw new HttpException(400, "top_k must be in 1..100");
    }
    long t0 = System.nanoTime();
    SearchService.Expanded ex = search.expand(query, expand, useCache);
    SearchService.Results res = search.keywordSearch(ex, topK);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("results", res.results());
    out.put("time_ms", (System.nanoTime() - t0) / 1_000_000);
    out.put("search_time_ms", res.timeMs());
    out.put("query_terms", ex.terms());
    if (expand) {
      out.put("expanded_terms", ex.expansion().terms());
      out.put("expansion_source", ex.expansion().source());
      out.put("expansion_time_ms", ex.expansion().timeMs());
    }
    return HttpResponse.json(200, out);
  }

  private HttpResponse stats() {
    Indexer.Snapshot snap = indexer.snapshot();
    Map<String, Object> out = new LinkedHashMap<>();
    if (snap == null) {
      out.put("documents", 0);
      out.put("index_build_time_ms", 0);
      out.put("threads_used", 0);
    } else {
      Indexer.BuildStats s = snap.stats();
      out.put("documents", s.documentsIndexed());
      out.put("index_build_time_ms", s.timeMs());
      out.put("threads_used", s.threads());
      out.put("impl", s.impl());
      out.put("mapper", s.mapper());
      out.put("partitions", s.partitions());
      out.put("terms", snap.index().termCount());
      out.put("postings", snap.index().postingCount());
      out.put("avg_doc_length", Math.round(snap.index().docs().averageLength()));
      out.put("phases", s.phases());
    }
    out.put("indexing", indexer.isBuilding());
    out.put("requests_served", metrics.requests());
    out.put("open_connections", metrics.openConnections());
    out.put("http_threads", metrics.httpThreads());
    out.put("cpus", Runtime.getRuntime().availableProcessors());
    out.put("memory", Memory.snapshot());
    return HttpResponse.json(200, out);
  }

  private HttpResponse staticFile(HttpRequest req) throws IOException {
    if (!req.method().equals("GET") && !req.method().equals("HEAD")) {
      return HttpResponse.error(404, "Not found");
    }
    String p = req.path().equals("/") ? "/index.html" : req.path();
    if (p.startsWith("/api/") || p.contains("..") || p.contains("\\")) {
      return HttpResponse.error(404, "Not found");
    }
    Path file = webDir.resolve(p.substring(1)).normalize();
    if (!file.startsWith(webDir.normalize()) || !Files.isRegularFile(file)) {
      return HttpResponse.error(404, "Not found");
    }
    return new HttpResponse(200, contentType(file), Files.readAllBytes(file))
        .header("Cache-Control", "no-cache")
        .header("X-Content-Type-Options", "nosniff");
  }

  private static String contentType(Path file) {
    String n = file.getFileName().toString();
    if (n.endsWith(".html")) {
      return "text/html; charset=utf-8";
    } else if (n.endsWith(".js")) {
      return "text/javascript; charset=utf-8";
    } else if (n.endsWith(".css")) {
      return "text/css; charset=utf-8";
    } else if (n.endsWith(".svg")) {
      return "image/svg+xml";
    }
    return "application/octet-stream";
  }

  static Map<String, Object> parseBody(HttpRequest req) {
    try {
      return Json.parseObject(req.bodyText());
    } catch (Json.ParseException e) {
      throw new HttpException(400, "Invalid JSON body: " + e.getMessage());
    }
  }

  static int intField(Map<String, Object> body, String name, int def) {
    Object v = body.get(name);
    if (v == null) {
      return def;
    }
    if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
      return l.intValue();
    }
    if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e9) {
      return d.intValue();
    }
    if (v instanceof String s) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        // falls through to the error
      }
    }
    throw new HttpException(400, "Field '" + name + "' must be an integer");
  }

  static String stringField(Map<String, Object> body, String name, String def) {
    Object v = body.get(name);
    if (v == null) {
      return def;
    }
    if (v instanceof String s) {
      return s.trim();
    }
    throw new HttpException(400, "Field '" + name + "' must be a string");
  }

  private static void warnUnknown(Map<String, Object> body, Set<String> known, String where) {
    List<String> unknown = body.keySet().stream().filter(k -> !known.contains(k)).toList();
    if (!unknown.isEmpty()) {
      System.err.println("[api] " + where + ": ignoring unknown fields " + unknown);
    }
  }
}
