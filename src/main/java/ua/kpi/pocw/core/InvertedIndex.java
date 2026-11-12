package ua.kpi.pocw.core;

import java.util.Map;

/**
 * Immutable inverted index: term to postings sorted by document id.
 *
 * <p>Terms are split into partitions by hash; each partition is a {@link Map} that is never
 * modified after construction. The index is published to readers through a volatile reference
 * ({@link java.util.concurrent.atomic.AtomicReference} in {@link Indexer}), so all writes made
 * while building happen-before any read, and readers need no locks at all.
 */
public final class InvertedIndex {
  private final Map<String, long[]>[] partitions;
  private final DocumentTable docs;
  private final long termCount;
  private final long postingCount;

  InvertedIndex(Map<String, long[]>[] partitions, DocumentTable docs) {
    this.partitions = partitions;
    this.docs = docs;
    long terms = 0;
    long postings = 0;
    for (Map<String, long[]> p : partitions) {
      terms += p.size();
      for (long[] list : p.values()) {
        postings += list.length;
      }
    }
    this.termCount = terms;
    this.postingCount = postings;
  }

  /** Maps a term to a partition; shared by builders and readers. */
  public static int partitionOf(String term, int partitions) {
    int h = term.hashCode() * 0x9E3779B9; // Fibonacci hashing spreads similar strings
    return Math.floorMod(h ^ (h >>> 16), partitions);
  }

  /** Returns postings of {@code term} (packed, see {@link PostingList}) or {@code null}. */
  public long[] postings(String term) {
    return partitions[partitionOf(term, partitions.length)].get(term);
  }

  public int documentFrequency(String term) {
    long[] p = postings(term);
    return p == null ? 0 : p.length;
  }

  /** Term frequency of {@code term} in {@code doc}, found by binary search; 0 if absent. */
  public int termFrequency(String term, int doc) {
    long[] p = postings(term);
    if (p == null) {
      return 0;
    }
    int lo = 0;
    int hi = p.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int d = PostingList.docId(p[mid]);
      if (d < doc) {
        lo = mid + 1;
      } else if (d > doc) {
        hi = mid - 1;
      } else {
        return PostingList.tf(p[mid]);
      }
    }
    return 0;
  }

  public DocumentTable docs() {
    return docs;
  }

  public int partitionCount() {
    return partitions.length;
  }

  public long termCount() {
    return termCount;
  }

  public long postingCount() {
    return postingCount;
  }
}
