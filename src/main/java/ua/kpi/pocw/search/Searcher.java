package ua.kpi.pocw.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import ua.kpi.pocw.core.DocumentTable;
import ua.kpi.pocw.core.InvertedIndex;
import ua.kpi.pocw.core.PostingList;

/**
 * Okapi BM25 ranking over an immutable {@link InvertedIndex}.
 *
 * <p>score(d) = sum over query terms t of w(t) * idf(t) * tf * (k1 + 1) / (tf + k1 * (1 - b + b *
 * |d| / avgdl)), where idf(t) = ln(1 + (N - df + 0.5) / (df + 0.5)) and w(t) is 1 for terms typed
 * by the user and {@link #EXPANSION_WEIGHT} for terms added by the LLM. The returned {@code score}
 * is BM25 divided by the best BM25 of the query, i.e. it lies in (0, 1]; the raw value is kept in
 * {@code bm25}. Ties are broken by document id, which makes results deterministic.
 *
 * <p>The searcher holds no mutable state; each call allocates its own accumulator, so any number
 * of threads can search the same index concurrently without locks.
 */
public final class Searcher {
  public static final double K1 = 1.2;
  public static final double B = 0.75;
  public static final double EXPANSION_WEIGHT = 0.4;

  /** One ranked hit. */
  public record Hit(int doc, double bm25, double score, List<String> matchedTerms) {}

  private Searcher() {}

  public static List<Hit> search(
      InvertedIndex index, List<String> userTerms, List<String> expansionTerms, int topK) {
    DocumentTable docs = index.docs();
    int n = docs.size();
    Map<String, Double> weights = new LinkedHashMap<>();
    for (String t : userTerms) {
      weights.put(t, 1.0);
    }
    for (String t : expansionTerms) {
      weights.putIfAbsent(t, EXPANSION_WEIGHT);
    }
    if (weights.isEmpty() || n == 0) {
      return List.of();
    }
    double avgdl = Math.max(1.0, docs.averageLength());
    double[] acc = new double[n];
    boolean any = false;
    for (Map.Entry<String, Double> e : weights.entrySet()) {
      long[] postings = index.postings(e.getKey());
      if (postings == null) {
        continue;
      }
      any = true;
      int df = postings.length;
      double idf = Math.log(1.0 + (docs.readableCount() - df + 0.5) / (df + 0.5));
      double w = e.getValue() * idf;
      for (long p : postings) {
        int d = PostingList.docId(p);
        int tf = PostingList.tf(p);
        double norm = K1 * (1 - B + B * docs.length(d) / avgdl);
        acc[d] += w * tf * (K1 + 1) / (tf + norm);
      }
    }
    if (!any) {
      return List.of();
    }
    // Min-heap of the best topK documents: worst on top.
    PriorityQueue<Integer> heap =
        new PriorityQueue<>(
            topK + 1,
            (a, b) -> acc[a] != acc[b] ? Double.compare(acc[a], acc[b]) : Integer.compare(b, a));
    for (int d = 0; d < n; d++) {
      if (acc[d] <= 0) {
        continue;
      }
      if (heap.size() < topK) {
        heap.add(d);
      } else {
        int worst = heap.peek();
        if (acc[d] > acc[worst] || (acc[d] == acc[worst] && d < worst)) {
          heap.poll();
          heap.add(d);
        }
      }
    }
    List<Integer> ranked = new ArrayList<>(heap);
    ranked.sort((a, b) -> acc[a] != acc[b] ? Double.compare(acc[b], acc[a]) : Integer.compare(a, b));
    double best = ranked.isEmpty() ? 1 : acc[ranked.get(0)];
    List<Hit> hits = new ArrayList<>(ranked.size());
    for (int d : ranked) {
      List<String> matched = new ArrayList<>();
      for (String t : weights.keySet()) {
        if (index.termFrequency(t, d) > 0) {
          matched.add(t);
        }
      }
      hits.add(new Hit(d, acc[d], acc[d] / best, matched));
    }
    return hits;
  }
}
