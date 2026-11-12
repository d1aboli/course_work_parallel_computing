package ua.kpi.pocw.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Registry of documents: dense id {@code 0..n-1} to external id, file path and length in tokens.
 *
 * <p>Ids are positions in the sorted file list, so they do not depend on the thread count. During
 * a build each slot of {@code lengths} and {@code readFailed} is written by exactly one map task;
 * the writes become visible to the finishing thread through {@link java.util.concurrent.Future#get}
 * (happens-before). After {@link #seal()} the table is read-only.
 */
public final class DocumentTable {
  private final String[] ids;
  private final Path[] paths;
  private final int[] lengths;
  private final boolean[] readFailed;
  private long totalTokens;
  private int readable;

  public DocumentTable(Path root, List<Path> files) {
    int n = files.size();
    ids = new String[n];
    paths = new Path[n];
    lengths = new int[n];
    readFailed = new boolean[n];
    for (int i = 0; i < n; i++) {
      Path p = files.get(i);
      paths[i] = p;
      String rel = root.relativize(p).toString().replace('\\', '/');
      ids[i] = rel.endsWith(".txt") ? rel.substring(0, rel.length() - 4) : rel;
    }
  }

  void setLength(int doc, int tokens) {
    lengths[doc] = tokens;
  }

  void markFailed(int doc) {
    readFailed[doc] = true;
  }

  /** Computes aggregate statistics; called once, after all map tasks have completed. */
  void seal() {
    long total = 0;
    int ok = 0;
    for (int i = 0; i < lengths.length; i++) {
      total += lengths[i];
      if (!readFailed[i]) {
        ok++;
      }
    }
    totalTokens = total;
    readable = ok;
  }

  public int size() {
    return ids.length;
  }

  /** Number of documents that were read successfully. */
  public int readableCount() {
    return readable;
  }

  public String id(int doc) {
    return ids[doc];
  }

  public Path path(int doc) {
    return paths[doc];
  }

  public int length(int doc) {
    return lengths[doc];
  }

  public long totalTokens() {
    return totalTokens;
  }

  public double averageLength() {
    return readable == 0 ? 0 : (double) totalTokens / readable;
  }
}
