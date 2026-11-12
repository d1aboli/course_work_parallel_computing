package ua.kpi.pocw.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ua.kpi.pocw.core.DocumentTable;
import ua.kpi.pocw.core.Indexer;
import ua.kpi.pocw.core.InvertedIndex;
import ua.kpi.pocw.core.Tokenizer;
import ua.kpi.pocw.llm.QueryExpander;
import ua.kpi.pocw.net.HttpException;
import ua.kpi.pocw.search.Searcher;
import ua.kpi.pocw.search.Snippets;

/**
 * Search pipeline shared by the REST and WebSocket front ends: query expansion, then keyword
 * search over the current index snapshot.
 *
 * <p>The snapshot is taken <i>after</i> the expansion stage, so a request that waits for the LLM
 * does not pin an index that a concurrent rebuild has already replaced.
 */
public final class SearchService {
  public static final int DEFAULT_TOP_K = 10;

  private final Indexer indexer;
  private final QueryExpander expander;

  public SearchService(Indexer indexer, QueryExpander expander) {
    this.indexer = indexer;
    this.expander = expander;
  }

  /** Stage 1: tokenized query and its expansion. */
  public record Expanded(String original, List<String> terms, QueryExpander.Expansion expansion) {}

  /** Stage 2: ranked results. */
  public record Results(List<Map<String, Object>> results, long timeMs, int matchedDocs) {}

  public Expanded expand(String query, boolean enabled, boolean useCache) {
    List<String> terms = Tokenizer.tokenizeQuery(query);
    QueryExpander.Expansion exp =
        enabled
            ? expander.expand(query, terms, useCache)
            : new QueryExpander.Expansion(List.of(), "off", 0);
    return new Expanded(query, terms, exp);
  }

  public Results keywordSearch(Expanded q, int topK) {
    Indexer.Snapshot snap = indexer.snapshot();
    if (snap == null) {
      throw new HttpException(409, "Index is not built yet; call POST /api/index first");
    }
    long t0 = System.nanoTime();
    InvertedIndex index = snap.index();
    List<Searcher.Hit> hits = Searcher.search(index, q.terms(), q.expansion().terms(), topK);
    DocumentTable docs = index.docs();
    // Snippet prefers the rarest user term, then expansion terms.
    List<String> userByRarity = new ArrayList<>(q.terms());
    userByRarity.sort(Comparator.comparingInt(index::documentFrequency));
    List<Map<String, Object>> out = new ArrayList<>(hits.size());
    for (Searcher.Hit h : hits) {
      List<String> order = new ArrayList<>();
      for (String t : userByRarity) {
        if (h.matchedTerms().contains(t)) {
          order.add(t);
        }
      }
      for (String t : h.matchedTerms()) {
        if (!order.contains(t)) {
          order.add(t);
        }
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("doc_id", docs.id(h.doc()));
      r.put("score", round(h.score(), 4));
      r.put("snippet", Snippets.build(docs.path(h.doc()), order));
      r.put("bm25", round(h.bm25(), 4));
      r.put("matched_terms", h.matchedTerms());
      out.add(r);
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    return new Results(out, ms, hits.size());
  }

  private static double round(double v, int digits) {
    double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }
}
