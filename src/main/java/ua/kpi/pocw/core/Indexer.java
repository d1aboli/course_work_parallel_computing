package ua.kpi.pocw.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Builds the inverted index in parallel and publishes it with build-then-swap.
 *
 * <p>Pipeline of one build:
 *
 * <ol>
 *   <li><b>list</b> (calling thread): collect {@code *.txt} files and sort them; the position in
 *       the sorted list is the document id, so ids do not depend on the thread count;
 *   <li><b>map</b> (pool): the list is cut into batches of {@code batchSize} documents; each task
 *       reads and tokenizes its documents into a private map without any synchronization;
 *   <li><b>merge</b> (pool, same task): the private map is handed to the {@link IndexBuilder};
 *   <li><b>finalize</b> (pool): one task per partition sorts and freezes its posting lists;
 *   <li><b>swap</b>: the new {@link Snapshot} replaces the old one through an {@link
 *       AtomicReference}; searches running on the old snapshot finish undisturbed.
 * </ol>
 *
 * <p>Builds are serialized by {@code buildLock}; a concurrent build request is rejected rather
 * than queued.
 */
public final class Indexer {
  /** Parameters of one build. */
  public record BuildRequest(int threads, int partitions, int batchSize, String impl) {
    public BuildRequest {
      if (threads < 1 || threads > 256) {
        throw new IllegalArgumentException("threads must be in 1..256");
      }
      if (partitions < 1 || partitions > 4096) {
        throw new IllegalArgumentException("partitions must be in 1..4096");
      }
      if (batchSize < 1 || batchSize > 10_000) {
        throw new IllegalArgumentException("batch_size must be in 1..10000");
      }
    }
  }

  /** Index plus the statistics of the build that produced it. Immutable. */
  public record Snapshot(InvertedIndex index, BuildStats stats) {}

  /** Statistics of one build. */
  public record BuildStats(
      int documentsIndexed,
      int documentsFailed,
      long timeMs,
      int threads,
      int partitions,
      int batchSize,
      int tasks,
      String impl,
      Map<String, Object> phases,
      Map<String, Object> sync) {}

  /** Thrown when a build is already running. */
  public static final class BuildInProgressException extends RuntimeException {
    public BuildInProgressException() {
      super("Index build already in progress");
    }
  }

  /** Thrown when the corpus produced no readable documents. */
  public static final class EmptyCorpusException extends RuntimeException {
    public EmptyCorpusException(String message) {
      super(message);
    }
  }

  private final Path dataDir;
  private final AtomicReference<Snapshot> current = new AtomicReference<>();
  private final ReentrantLock buildLock = new ReentrantLock();

  public Indexer(Path dataDir) {
    this.dataDir = dataDir;
  }

  /** Current snapshot or {@code null} before the first build. Lock-free. */
  public Snapshot snapshot() {
    return current.get();
  }

  public boolean isBuilding() {
    return buildLock.isLocked();
  }

  public BuildStats build(BuildRequest req) throws IOException, InterruptedException {
    if (!buildLock.tryLock()) {
      throw new BuildInProgressException();
    }
    try {
      return doBuild(req);
    } finally {
      buildLock.unlock();
    }
  }

  private BuildStats doBuild(BuildRequest req) throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    List<Path> files = listFiles(dataDir);
    long tList = System.nanoTime();
    if (files.isEmpty()) {
      throw new EmptyCorpusException("No *.txt files under " + dataDir);
    }
    DocumentTable docs = new DocumentTable(dataDir, files);
    IndexBuilder builder = IndexBuilder.create(req.impl(), req.partitions());

    LongAdder readNs = new LongAdder();
    LongAdder tokenizeNs = new LongAdder();
    LongAdder mergeNs = new LongAdder();
    LongAdder bytesRead = new LongAdder();

    int n = files.size();
    int tasks = (n + req.batchSize() - 1) / req.batchSize();
    long tMapEnd;
    long tFinalizeEnd;
    InvertedIndex index;
    try (ThreadPool pool = new ThreadPool(req.threads(), "index")) {
      List<Future<?>> futures = new ArrayList<>(tasks);
      for (int start = 0; start < n; start += req.batchSize()) {
        final int from = start;
        final int to = Math.min(n, start + req.batchSize());
        futures.add(
            pool.submit(
                () -> mapBatch(docs, from, to, builder, readNs, tokenizeNs, mergeNs, bytesRead)));
      }
      awaitAll(futures);
      tMapEnd = System.nanoTime();
      docs.seal();
      try {
        index = builder.finish(pool, docs);
      } catch (ExecutionException e) {
        throw unwrap(e);
      }
      tFinalizeEnd = System.nanoTime();
    }

    if (docs.readableCount() == 0) {
      throw new EmptyCorpusException("None of " + n + " files could be read");
    }

    Map<String, Object> phases = new LinkedHashMap<>();
    phases.put("list_ms", ms(tList - t0));
    phases.put("map_merge_wall_ms", ms(tMapEnd - tList));
    phases.put("finalize_wall_ms", ms(tFinalizeEnd - tMapEnd));
    phases.put("read_cpu_ms", ms(readNs.sum()));
    phases.put("tokenize_cpu_ms", ms(tokenizeNs.sum()));
    phases.put("merge_cpu_ms", ms(mergeNs.sum()));
    phases.put("bytes_read", bytesRead.sum());
    phases.put("terms", index.termCount());
    phases.put("postings", index.postingCount());

    long totalMs = ms(tFinalizeEnd - t0);
    BuildStats stats =
        new BuildStats(
            docs.readableCount(),
            n - docs.readableCount(),
            totalMs,
            req.threads(),
            req.partitions(),
            req.batchSize(),
            tasks,
            builder.name(),
            phases,
            builder.stats());
    current.set(new Snapshot(index, stats)); // volatile write: safe publication to readers
    return stats;
  }

  private static void mapBatch(
      DocumentTable docs,
      int from,
      int to,
      IndexBuilder builder,
      LongAdder readNs,
      LongAdder tokenizeNs,
      LongAdder mergeNs,
      LongAdder bytesRead) {
    HashMap<String, PostingList> local = new HashMap<>(8192);
    HashMap<String, int[]> counts = new HashMap<>(4096);
    long read = 0;
    long tokenize = 0;
    for (int doc = from; doc < to; doc++) {
      long a = System.nanoTime();
      byte[] data;
      try {
        data = Files.readAllBytes(docs.path(doc));
      } catch (IOException | UncheckedIOException e) {
        docs.markFailed(doc);
        read += System.nanoTime() - a;
        continue;
      }
      long b = System.nanoTime();
      counts.clear();
      int[] tokens = new int[1];
      Tokenizer.tokenize(
          data,
          data.length,
          t -> {
            tokens[0]++;
            int[] c = counts.get(t);
            if (c == null) {
              counts.put(t, new int[] {1});
            } else {
              c[0]++;
            }
          });
      for (Map.Entry<String, int[]> e : counts.entrySet()) {
        PostingList pl = local.get(e.getKey());
        if (pl == null) {
          pl = new PostingList(2);
          local.put(e.getKey(), pl);
        }
        pl.add(doc, e.getValue()[0]);
      }
      docs.setLength(doc, tokens[0]);
      long c = System.nanoTime();
      read += b - a;
      tokenize += c - b;
      bytesRead.add(data.length);
    }
    long m0 = System.nanoTime();
    builder.addBatch(local);
    mergeNs.add(System.nanoTime() - m0);
    readNs.add(read);
    tokenizeNs.add(tokenize);
  }

  static List<Path> listFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      throw new IOException("Data directory not found: " + root);
    }
    try (Stream<Path> s = Files.walk(root)) {
      return s.filter(p -> p.getFileName().toString().endsWith(".txt"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private static void awaitAll(List<Future<?>> futures) throws InterruptedException {
    for (Future<?> f : futures) {
      try {
        f.get();
      } catch (ExecutionException e) {
        throw unwrap(e);
      }
    }
  }

  private static RuntimeException unwrap(ExecutionException e) {
    Throwable c = e.getCause();
    if (c instanceof RuntimeException re) {
      return re;
    }
    if (c instanceof Error err) {
      throw err;
    }
    return new IllegalStateException(c);
  }

  private static long ms(long nanos) {
    return nanos / 1_000_000;
  }
}
