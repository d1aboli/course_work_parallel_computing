package ua.kpi.pocw.core;

import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Thread-safe accumulator of postings produced by parallel map tasks.
 *
 * <p>{@link #addBatch} may be called concurrently from any number of threads. {@link #finish} is
 * called once, after every {@code addBatch} call has returned, and converts the accumulated data
 * into an immutable {@link InvertedIndex}, using the given pool for parallel work.
 */
public interface IndexBuilder {
  /** Merges postings of one batch; the builder takes ownership of the map and its lists. */
  void addBatch(Map<String, PostingList> batch);

  InvertedIndex finish(ThreadPool pool, DocumentTable docs)
      throws InterruptedException, ExecutionException;

  /** Implementation name used in API responses and benchmarks. */
  String name();

  /** Synchronization counters collected during the build (for the scalability study). */
  Map<String, Object> stats();

  /** Factory by name: {@code partitioned} (default) or {@code striped}. */
  static IndexBuilder create(String impl, int partitions) {
    return switch (impl) {
      case "partitioned" -> new PartitionedIndexBuilder(partitions);
      case "striped" -> new StripedIndexBuilder(partitions);
      default -> throw new IllegalArgumentException("Unknown index implementation: " + impl);
    };
  }
}
