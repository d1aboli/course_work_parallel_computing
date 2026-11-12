package ua.kpi.pocw.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import ua.kpi.pocw.util.Json;

/**
 * Command-line client of the search server.
 *
 * <pre>
 *   search "query words"              keyword search
 *   search --expand "query words"     search with LLM query expansion
 *   search --top 5 "query"            limit the number of results
 *   search --index 8                  build the index with 8 threads
 *   search --stats | --health         print server statistics / health
 *   search --host http://host:8080 …  talk to another server (default $SEARCH_URL or :8080)
 * </pre>
 *
 * Exit codes: 0 success, 1 server or network error, 2 usage error.
 */
public final class SearchCli {
  private static final String RESET = "\u001b[0m";
  private static final String BOLD = "\u001b[1m";
  private static final String DIM = "\u001b[2m";
  private static final String YELLOW = "\u001b[33m";
  private static final String CYAN = "\u001b[36m";

  private final HttpClient http =
      HttpClient.newBuilder()
          .proxy(HttpClient.Builder.NO_PROXY)
          .connectTimeout(Duration.ofSeconds(3))
          .build();
  private final String host;
  private final boolean color;

  private SearchCli(String host, boolean color) {
    this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    this.color = color;
  }

  public static void main(String[] args) {
    String host = System.getenv().getOrDefault("SEARCH_URL", "http://localhost:8080");
    boolean expand = false;
    boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
    int top = 10;
    String command = "search";
    Integer indexThreads = null;
    List<String> words = new ArrayList<>();
    try {
      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--host" -> host = args[++i];
          case "--expand", "-e" -> expand = true;
          case "--top", "-n" -> top = Integer.parseInt(args[++i]);
          case "--index" -> {
            command = "index";
            indexThreads = Integer.parseInt(args[++i]);
          }
          case "--stats" -> command = "stats";
          case "--health" -> command = "health";
          case "--no-color" -> color = false;
          case "--help", "-h" -> {
            usage();
            return;
          }
          default -> words.add(args[i]);
        }
      }
    } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
      usage();
      System.exit(2);
    }
    SearchCli cli = new SearchCli(host, color);
    int code;
    try {
      code =
          switch (command) {
            case "health" -> cli.get("/api/health");
            case "stats" -> cli.get("/api/stats");
            case "index" -> cli.index(indexThreads);
            default -> {
              if (words.isEmpty()) {
                usage();
                yield 2;
              }
              yield cli.search(String.join(" ", words), expand, top);
            }
          };
    } catch (IOException e) {
      System.err.println("error: cannot reach " + host + " (" + e.getMessage() + ")");
      code = 1;
    } catch (InterruptedException e) {
      code = 1;
    }
    System.exit(code);
  }

  private static void usage() {
    System.err.println(
        "usage: search [--host URL] [--expand] [--top N] \"query\"\n"
            + "       search --index THREADS | --stats | --health");
  }

  private int get(String path) throws IOException, InterruptedException {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(host + path)).timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
    System.out.println(r.body());
    return r.statusCode() == 200 ? 0 : 1;
  }

  private HttpResponse<String> post(String path, Object body, Duration timeout)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create(host + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private int index(int threads) throws IOException, InterruptedException {
    System.out.println("building index with " + threads + " threads...");
    HttpResponse<String> r = post("/api/index", Json.obj("threads", threads), Duration.ofMinutes(30));
    Map<String, Object> m = Json.parseObject(r.body());
    if (r.statusCode() != 200) {
      System.err.println("error " + r.statusCode() + ": " + m.get("error"));
      return 1;
    }
    System.out.printf("indexed %s documents in %s ms (%s, %s partitions)%n",
        m.get("documents_indexed"), m.get("time_ms"), m.get("impl"), m.get("partitions"));
    return 0;
  }

  @SuppressWarnings("unchecked")
  private int search(String query, boolean expand, int top)
      throws IOException, InterruptedException {
    HttpResponse<String> r =
        post(
            "/api/search",
            Json.obj("query", query, "mode", "keyword", "expand", expand, "top_k", top),
            Duration.ofSeconds(60));
    Map<String, Object> m = Json.parseObject(r.body());
    if (r.statusCode() != 200) {
      System.err.println("error " + r.statusCode() + ": " + m.get("error"));
      return 1;
    }
    List<String> highlight = new ArrayList<>((List<String>) m.get("query_terms"));
    if (expand) {
      List<String> extra = (List<String>) m.get("expanded_terms");
      System.out.println(paint(DIM, "expanded with: " + (extra.isEmpty()
          ? "(nothing, source=" + m.get("expansion_source") + ")" : String.join(", ", extra))));
      highlight.addAll(extra);
    }
    List<Map<String, Object>> results = (List<Map<String, Object>>) m.get("results");
    System.out.printf("%d result(s) in %s ms%n%n", results.size(), m.get("time_ms"));
    int rank = 1;
    for (Map<String, Object> res : results) {
      System.out.printf("%2d. %s  %s%n", rank++, paint(BOLD + CYAN, (String) res.get("doc_id")),
          paint(DIM, "score " + res.get("score")));
      System.out.println("    " + highlight((String) res.get("snippet"), highlight));
    }
    return 0;
  }

  private String paint(String style, String text) {
    return color ? style + text + RESET : text;
  }

  private String highlight(String snippet, List<String> terms) {
    if (!color || terms.isEmpty()) {
      return snippet;
    }
    String alt = String.join("|", terms.stream().map(Pattern::quote).toList());
    return Pattern.compile("(?i)\\b(" + alt + ")\\b")
        .matcher(snippet)
        .replaceAll(mr -> YELLOW + BOLD + mr.group() + RESET);
  }
}
