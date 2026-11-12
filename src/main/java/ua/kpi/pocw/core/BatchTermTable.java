package ua.kpi.pocw.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Allocation-lean term table for one map task ("arena" mapper).
 *
 * <p>The straightforward mapper creates a {@link String} for every token and counts terms in a
 * per-document {@link HashMap}: for 1.6 GB of text that is ~300 million short-lived objects, and
 * the measurements showed that per-token cost grows with the thread count (allocation and GC
 * pressure on the shared heap and memory bus). This table instead hashes the raw bytes of a token
 * in place and looks it up in an open-addressing table owned by the task; a {@code String} is
 * created only the first time a term appears in the batch. Term frequencies are accumulated in
 * {@code curDoc}/{@code curCount} slots and flushed into the posting list when the document id
 * changes, so no per-document map exists at all.
 *
 * <p>Token rules are identical to {@link Tokenizer} (the tests compare both mappers), and the
 * hash is computed exactly like {@link String#hashCode()} of the lower-cased token.
 *
 * <p>Not thread-safe: one instance per map task.
 */
final class BatchTermTable {
  private int[] table; // entry index + 1, 0 = empty
  private int mask;
  private int size;

  private String[] terms;
  private int[] hashes;
  private boolean[] stop;
  private int[] curDoc;
  private int[] curCount;
  private PostingList[] lists;

  BatchTermTable(int expectedTerms) {
    int cap = Integer.highestOneBit(Math.max(16, expectedTerms * 2 - 1)) << 1;
    table = new int[cap];
    mask = cap - 1;
    int n = Math.max(16, expectedTerms);
    terms = new String[n];
    hashes = new int[n];
    stop = new boolean[n];
    curDoc = new int[n];
    curCount = new int[n];
    lists = new PostingList[n];
  }

  /** Tokenizes one document and accumulates its terms; returns the number of kept tokens. */
  int addDocument(byte[] data, int length, int doc) {
    int kept = 0;
    int i = 0;
    while (i < length) {
      while (i < length && !Tokenizer.isWordByte(data[i])) {
        i++;
      }
      int start = i;
      int h = 0;
      while (i < length && Tokenizer.isWordByte(data[i])) {
        h = 31 * h + lower(data[i]);
        i++;
      }
      int len = i - start;
      if (len < Tokenizer.MIN_LEN || len > Tokenizer.MAX_LEN) {
        continue;
      }
      int e = findOrInsert(data, start, len, h);
      if (stop[e]) {
        continue;
      }
      kept++;
      if (curDoc[e] == doc) {
        curCount[e]++;
      } else {
        if (curCount[e] > 0) {
          lists[e].add(curDoc[e], curCount[e]);
        }
        curDoc[e] = doc;
        curCount[e] = 1;
      }
    }
    return kept;
  }

  private static int lower(byte b) {
    return (b >= 'A' && b <= 'Z') ? b + 32 : b;
  }

  private int findOrInsert(byte[] data, int start, int len, int h) {
    int spread = h ^ (h >>> 16);
    int slot = spread & mask;
    while (true) {
      int v = table[slot];
      if (v == 0) {
        return insert(data, start, len, h, slot);
      }
      int e = v - 1;
      if (hashes[e] == h && equalsLowered(terms[e], data, start, len)) {
        return e;
      }
      slot = (slot + 1) & mask; // linear probing
    }
  }

  private static boolean equalsLowered(String term, byte[] data, int start, int len) {
    if (term.length() != len) {
      return false;
    }
    for (int k = 0; k < len; k++) {
      if (term.charAt(k) != lower(data[start + k])) {
        return false;
      }
    }
    return true;
  }

  private int insert(byte[] data, int start, int len, int h, int slot) {
    if (size == terms.length) {
      growEntries();
    }
    byte[] buf = new byte[len];
    for (int k = 0; k < len; k++) {
      buf[k] = (byte) lower(data[start + k]);
    }
    String term = new String(buf, StandardCharsets.ISO_8859_1);
    int e = size++;
    terms[e] = term;
    hashes[e] = h;
    stop[e] = Tokenizer.isStopWord(term);
    curDoc[e] = -1;
    curCount[e] = 0;
    lists[e] = stop[e] ? null : new PostingList(2);
    table[slot] = e + 1;
    if (size * 2 > table.length) {
      rehash();
    }
    return e;
  }

  private void growEntries() {
    int n = terms.length * 2;
    terms = java.util.Arrays.copyOf(terms, n);
    hashes = java.util.Arrays.copyOf(hashes, n);
    stop = java.util.Arrays.copyOf(stop, n);
    curDoc = java.util.Arrays.copyOf(curDoc, n);
    curCount = java.util.Arrays.copyOf(curCount, n);
    lists = java.util.Arrays.copyOf(lists, n);
  }

  private void rehash() {
    int cap = table.length * 2;
    int[] t = new int[cap];
    int m = cap - 1;
    for (int e = 0; e < size; e++) {
      int h = hashes[e];
      int slot = (h ^ (h >>> 16)) & m;
      while (t[slot] != 0) {
        slot = (slot + 1) & m;
      }
      t[slot] = e + 1;
    }
    table = t;
    mask = m;
  }

  /** Flushes pending counts and returns term -> postings for the index builder. */
  Map<String, PostingList> drain() {
    HashMap<String, PostingList> out = new HashMap<>((int) (size / 0.75f) + 1);
    for (int e = 0; e < size; e++) {
      if (stop[e]) {
        continue;
      }
      if (curCount[e] > 0) {
        lists[e].add(curDoc[e], curCount[e]);
      }
      out.put(terms[e], lists[e]);
    }
    return out;
  }
}
