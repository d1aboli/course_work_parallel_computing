package ua.kpi.pocw.core;

import java.util.Arrays;

/**
 * Growable list of postings used while an index is being built.
 *
 * <p>A posting is packed into one {@code long}: the document id in the high 32 bits and the term
 * frequency in the low 32 bits. Packing keeps a posting in 8 bytes and makes "sort by document id"
 * a plain {@link Arrays#sort(long[])} because ids are non-negative.
 *
 * <p>Not thread-safe: a list is owned by one thread at a time (a map task, or the current owner of
 * a partition). Ownership is transferred through the builders' synchronization.
 */
public final class PostingList {
  private long[] data;
  private int size;
  private boolean sorted = true;

  public PostingList() {
    this(4);
  }

  public PostingList(int capacity) {
    data = new long[Math.max(1, capacity)];
  }

  public static long pack(int docId, int tf) {
    return ((long) docId << 32) | (tf & 0xFFFFFFFFL);
  }

  public static int docId(long posting) {
    return (int) (posting >>> 32);
  }

  public static int tf(long posting) {
    return (int) posting;
  }

  public void add(int docId, int tf) {
    long p = pack(docId, tf);
    if (size > 0 && data[size - 1] > p) {
      sorted = false;
    }
    ensure(size + 1);
    data[size++] = p;
  }

  /** Appends all postings of {@code other}; {@code other} must not be used afterwards. */
  public void addAll(PostingList other) {
    if (other.size == 0) {
      return;
    }
    if (!other.sorted || (size > 0 && data[size - 1] > other.data[0])) {
      sorted = false;
    }
    ensure(size + other.size);
    System.arraycopy(other.data, 0, data, size, other.size);
    size += other.size;
  }

  private void ensure(int needed) {
    if (needed > data.length) {
      data = Arrays.copyOf(data, Math.max(needed, data.length + (data.length >> 1) + 1));
    }
  }

  public int size() {
    return size;
  }

  public boolean isSorted() {
    return sorted;
  }

  /** Returns a trimmed array sorted by document id. */
  public long[] toSortedArray() {
    long[] out = Arrays.copyOf(data, size);
    if (!sorted) {
      Arrays.sort(out);
    }
    return out;
  }
}
