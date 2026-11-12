package ua.kpi.pocw.bench;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import ua.kpi.pocw.util.Json;

/**
 * Benchmark driver for the scalability study; runs next to the server (inside the container).
 *
 * <pre>
 *   bench index      --threads 1,2,4,8 --runs 3 --impl partitioned,striped --out index.csv
 *   bench throughput --clients 1,2,4,8,16,32 --duration 20 --warmup 5 [--expand] [--no-cache]
 *   bench memory     --rebuilds 8 --threads 8 [--no-gc]
 *   bench queries    --file test_queries.txt [--expand]
 * </pre>
 *
 * Index runs are interleaved: every round visits all configurations in a fresh random order, so
 * slow drifts (thermal throttling, page cache) spread evenly instead of biasing one setting.
 * Results go to {@code /app/bench/results} (mounted to {@code ./bench/results} on the host).
 */
public final class Bench {
  private final String host;
  private final int port;
  private final HttpClient http =
      HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

  private Bench(String url) {
    URI u = URI.create(url);
    this.host = u.getHost();
    this.port = u.getPort() < 0 ? 80 : u.getPort();
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("usage: bench index|throughput|memory|queries [options]");
      System.exit(2);
    }
    Map<String, String> opt = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        String key = args[i].substring(2);
        boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
        opt.put(key, flag ? "true" : args[++i]);
      }
    }
    Bench b = new Bench(opt.getOrDefault("host", "http://127.0.0.1:8080"));
    Path outDir = Path.of(opt.getOrDefault("dir", "/app/bench/results"));
    Files.createDirectories(outDir);
    switch (args[0]) {
      case "index" -> b.index(opt, outDir.resolve(opt.getOrDefault("out", "index.csv")));
      case "throughput" -> b.throughput(opt,
          outDir.resolve(opt.getOrDefault("out", "throughput.csv")));
      case "memory" -> b.memory(opt, outDir.resolve(opt.getOrDefault("out", "memory.csv")));
      case "queries" -> b.queries(opt, outDir.resolve(opt.getOrDefault("out", "queries.json")));
      default -> {
        System.err.println("unknown command " + args[0]);
        System.exit(2);
      }
    }
  }

  private static int[] ints(String csv) {
    return Arrays.stream(csv.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
  }

  private Map<String, Object> post(String path, Object body, Duration timeout) throws Exception {
    HttpResponse<String> r = http.send(
        HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    Map<String, Object> m = Json.parseObject(r.body());
    if (r.statusCode() != 200) {
      throw new IOException("HTTP " + r.statusCode() + " " + m.get("error"));
    }
    return m;
  }

  // ---------------------------------------------------------------- index build time

  @SuppressWarnings("unchecked")
  private void index(Map<String, String> opt, Path out) throws Exception {
    int[] threads = ints(opt.getOrDefault("threads", "1,2,4,6,8,10,12,16"));
    String[] impls = opt.getOrDefault("impl", "partitioned").split(",");
    int[] partitions = ints(opt.getOrDefault("partitions", "64"));
    int runs = Integer.parseInt(opt.getOrDefault("runs", "3"));
    int batch = Integer.parseInt(opt.getOrDefault("batch", "32"));
    Random rnd = new Random(Long.parseLong(opt.getOrDefault("seed", "2026")));

    List<Object[]> configs = new ArrayList<>();
    for (String impl : impls) {
      for (int p : partitions) {
        for (int t : threads) {
          configs.add(new Object[] {impl, p, t});
        }
      }
    }
    System.out.printf("warm-up build (JIT, page cache)...%n");
    post("/api/index", Json.obj("threads", threads[threads.length - 1], "impl", impls[0],
        "gc", true), Duration.ofHours(1));

    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
      w.println("run,impl,partitions,threads,time_ms,documents,list_ms,map_merge_wall_ms,"
          + "finalize_wall_ms,read_cpu_ms,tokenize_cpu_ms,merge_cpu_ms,sync1,sync2,heap_used_mb,"
          + "rss_mb");
      for (int run = 1; run <= runs; run++) {
        List<Object[]> order = new ArrayList<>(configs);
        Collections.shuffle(order, rnd);
        for (Object[] c : order) {
          Map<String, Object> r = post("/api/index", Json.obj("threads", c[2], "partitions",
              c[1], "batch_size", batch, "impl", c[0], "gc", true), Duration.ofHours(1));
          Map<String, Object> ph = (Map<String, Object>) r.get("phases");
          Map<String, Object> sy = (Map<String, Object>) r.get("sync");
          Map<String, Object> mem = (Map<String, Object>) r.get("memory");
          Object s1 = c[0].equals("striped") ? sy.get("lock_contended") : sy.get("handoffs");
          Object s2 = c[0].equals("striped") ? sy.get("lock_wait_ms")
              : sy.get("fragments_merged_by_other_thread");
          w.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n", run, c[0], c[1], c[2],
              r.get("time_ms"), r.get("documents_indexed"), ph.get("list_ms"),
              ph.get("map_merge_wall_ms"), ph.get("finalize_wall_ms"), ph.get("read_cpu_ms"),
              ph.get("tokenize_cpu_ms"), ph.get("merge_cpu_ms"), s1, s2,
              mem.get("heap_used_mb"), mem.get("rss_mb"));
          w.flush();
          System.out.printf("run %d  %-11s p=%-4s t=%-3s %7s ms%n", run, c[0], c[1], c[2],
              r.get("time_ms"));
        }
      }
    }
    System.out.println("written " + out);
  }

  // ---------------------------------------------------------------- memory over rebuilds

  @SuppressWarnings("unchecked")
  private void memory(Map<String, String> opt, Path out) throws Exception {
    int rebuilds = Integer.parseInt(opt.getOrDefault("rebuilds", "8"));
    int threads = Integer.parseInt(opt.getOrDefault("threads", "8"));
    boolean gc = !opt.containsKey("no-gc");
    boolean append = Files.exists(out);
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out,
        append ? new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.APPEND}
            : new java.nio.file.OpenOption[0]))) {
      if (!append) {
        w.println("mode,rebuild,time_ms,heap_used_mb,heap_committed_mb,rss_mb");
      }
      for (int i = 1; i <= rebuilds; i++) {
        Map<String, Object> r = post("/api/index", Json.obj("threads", threads, "gc", gc),
            Duration.ofHours(1));
        Map<String, Object> mem = (Map<String, Object>) r.get("memory");
        w.printf("%s,%d,%s,%s,%s,%s%n", gc ? "gc" : "no_gc", i, r.get("time_ms"),
            mem.get("heap_used_mb"), mem.get("heap_committed_mb"), mem.get("rss_mb"));
        w.flush();
        System.out.printf("rebuild %d: heap %s MiB, rss %s MiB%n", i, mem.get("heap_used_mb"),
            mem.get("rss_mb"));
      }
    }
    System.out.println("written " + out);
  }

  // ---------------------------------------------------------------- throughput

  private List<String> loadQueries(Map<String, String> opt) throws IOException {
    Path f = Path.of(opt.getOrDefault("queries", "/app/bench/queries.txt"));
    return Files.readAllLines(f).stream().map(String::strip)
        .filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
  }

  private void throughput(Map<String, String> opt, Path out) throws Exception {
    int[] clients = ints(opt.getOrDefault("clients", "1,2,4,8,16,32"));
    int duration = Integer.parseInt(opt.getOrDefault("duration", "20"));
    int warmup = Integer.parseInt(opt.getOrDefault("warmup", "5"));
    boolean expand = opt.containsKey("expand");
    boolean cache = !opt.containsKey("no-cache");
    List<String> queries = loadQueries(opt);
    boolean append = Files.exists(out);
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out,
        append ? new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.APPEND}
            : new java.nio.file.OpenOption[0]))) {
      if (!append) {
        w.println("mode,clients,duration_s,requests,errors,rps,mean_ms,p50_ms,p95_ms,p99_ms,"
            + "max_ms");
      }
      String mode = expand ? (cache ? "expand_cached" : "expand_uncached") : "keyword";
      for (int c : clients) {
        double[] row = load(c, warmup, duration, queries, expand, cache);
        w.printf(java.util.Locale.ROOT, "%s,%d,%d,%d,%d,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f%n", mode,
            c, duration, (long) row[0], (long) row[1], row[2], row[3], row[4], row[5], row[6],
            row[7]);
        w.flush();
        System.out.printf(java.util.Locale.ROOT,
            "%-16s clients=%-3d %9.1f req/s  p50 %.2f ms  p95 %.2f ms  p99 %.2f ms  errors %d%n",
            mode, c, row[2], row[4], row[5], row[6], (long) row[1]);
      }
    }
    System.out.println("written " + out);
  }

  /** Returns {requests, errors, rps, mean, p50, p95, p99, max} for one client count. */
  private double[] load(int clients, int warmupS, int durationS, List<String> queries,
      boolean expand, boolean cache) throws Exception {
    AtomicBoolean measuring = new AtomicBoolean(false);
    AtomicBoolean stop = new AtomicBoolean(false);
    long[][] lat = new long[clients][];
    int[] counts = new int[clients];
    int[] errors = new int[clients];
    CountDownLatch done = new CountDownLatch(clients);
    for (int i = 0; i < clients; i++) {
      final int id = i;
      Thread t = new Thread(() -> {
        long[] mine = new long[1 << 16];
        int n = 0;
        int err = 0;
        int q = id * 7;
        Socket s = null;
        try {
          while (!stop.get()) {
            if (s == null) {
              s = new Socket(host, port);
              s.setTcpNoDelay(true);
              s.setSoTimeout(120_000);
            }
            String query = queries.get(q++ % queries.size());
            String body = Json.stringify(Json.obj("query", query, "mode", "keyword", "expand",
                expand, "cache", cache));
            long t0 = System.nanoTime();
            int status;
            try {
              status = roundTrip(s, body);
              if (status >= 1000) { // server rotated the keep-alive connection: not an error
                status -= 1000;
                s.close();
                s = null;
              }
            } catch (IOException e) {
              status = -1;
              try {
                s.close();
              } catch (IOException ignored) {
                // reconnect
              }
              s = null;
            }
            long dt = System.nanoTime() - t0;
            if (measuring.get() && !stop.get()) {
              if (status == 200) {
                if (n == mine.length) {
                  mine = Arrays.copyOf(mine, n * 2);
                }
                mine[n++] = dt;
              } else {
                err++;
              }
            }
          }
        } catch (IOException e) {
          err++;
        } finally {
          try {
            if (s != null) {
              s.close();
            }
          } catch (IOException ignored) {
            // closing
          }
          lat[id] = Arrays.copyOf(mine, n);
          counts[id] = n;
          errors[id] = err;
          done.countDown();
        }
      }, "client-" + i);
      t.setDaemon(true);
      t.start();
    }
    Thread.sleep(warmupS * 1000L);
    long start = System.nanoTime();
    measuring.set(true);
    Thread.sleep(durationS * 1000L);
    stop.set(true);
    long elapsed = System.nanoTime() - start;
    done.await();
    int total = Arrays.stream(counts).sum();
    long[] all = new long[total];
    int k = 0;
    for (long[] l : lat) {
      System.arraycopy(l, 0, all, k, l.length);
      k += l.length;
    }
    Arrays.sort(all);
    double mean = total == 0 ? 0 : Arrays.stream(all).average().orElse(0) / 1e6;
    return new double[] {
      total,
      Arrays.stream(errors).sum(),
      total / (elapsed / 1e9),
      mean,
      pct(all, 0.50),
      pct(all, 0.95),
      pct(all, 0.99),
      total == 0 ? 0 : all[total - 1] / 1e6
    };
  }

  private static double pct(long[] sorted, double p) {
    if (sorted.length == 0) {
      return 0;
    }
    int idx = (int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
    return sorted[Math.max(0, idx)] / 1e6;
  }

  /**
   * Sends one keep-alive POST /api/search and reads the response; returns the status, plus 1000
   * if the server announced {@code Connection: close} (the caller must reconnect).
   */
  private int roundTrip(Socket s, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    OutputStream o = s.getOutputStream();
    o.write(("POST /api/search HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: application/json"
        + "\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
    o.write(b);
    o.flush();
    InputStream in = s.getInputStream();
    String status = line(in);
    int len = 0;
    boolean close = false;
    String h;
    while (!(h = line(in)).isEmpty()) {
      String lower = h.toLowerCase(java.util.Locale.ROOT);
      if (lower.startsWith("content-length:")) {
        len = Integer.parseInt(lower.substring(15).trim());
      } else if (lower.startsWith("connection:") && lower.contains("close")) {
        close = true;
      }
    }
    in.readNBytes(len);
    int code = Integer.parseInt(status.split(" ")[1]);
    return close ? code + 1000 : code;
  }

  private static String line(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder(64);
    int c;
    while ((c = in.read()) != '\n') {
      if (c < 0) {
        throw new IOException("eof");
      }
      if (c != '\r') {
        sb.append((char) c);
      }
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- functional queries

  @SuppressWarnings("unchecked")
  private void queries(Map<String, String> opt, Path out) throws Exception {
    Path f = Path.of(opt.getOrDefault("file", "/app/bench/test_queries.txt"));
    boolean expand = opt.containsKey("expand");
    List<Object> rows = new ArrayList<>();
    for (String line : Files.readAllLines(f)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String q = line.strip();
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("query", q);
      try {
        Map<String, Object> r = post("/api/search", Json.obj("query", q, "mode", "keyword",
            "expand", expand, "top_k", 3), Duration.ofSeconds(60));
        row.put("status", 200);
        row.put("time_ms", r.get("time_ms"));
        row.put("query_terms", r.get("query_terms"));
        row.put("expanded_terms", r.get("expanded_terms"));
        List<Map<String, Object>> res = (List<Map<String, Object>>) r.get("results");
        row.put("results", res.stream().map(x -> Json.obj("doc_id", x.get("doc_id"),
            "score", x.get("score"), "snippet", x.get("snippet"),
            "matched_terms", x.get("matched_terms"))).toList());
      } catch (IOException e) {
        row.put("status", e.getMessage());
      }
      rows.add(row);
      System.out.println(Json.stringify(row));
    }
    Files.writeString(out, Json.stringify(rows));
    System.out.println("written " + out);
  }
}
