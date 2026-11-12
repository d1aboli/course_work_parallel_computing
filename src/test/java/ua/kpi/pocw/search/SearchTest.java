package ua.kpi.pocw.search;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ua.kpi.pocw.core.Indexer;
import ua.kpi.pocw.core.InvertedIndex;
import ua.kpi.pocw.testing.Test;
import ua.kpi.pocw.testing.TestCorpus;

class SearchTest {
  private static InvertedIndex build(TestCorpus c) throws Exception {
    Indexer indexer = new Indexer(c.dir());
    indexer.build(new Indexer.BuildRequest(2, 4, 2, "partitioned"));
    return indexer.snapshot().index();
  }

  @Test
  void bm25PrefersRarerTermsAndHigherTf() throws Exception {
    try (TestCorpus c = new TestCorpus(0, 0, 1)) {
      c.write("a.txt", "whale whale whale ship");
      c.write("b.txt", "whale ship ship ship");
      c.write("c.txt", "ship harbor");
      c.write("d.txt", "ship harbor sea");
      InvertedIndex idx = build(c);
      List<Searcher.Hit> hits = Searcher.search(idx, List.of("whale"), List.of(), 10);
      equal(2, hits.size(), "two docs contain whale");
      equal("a", idx.docs().id(hits.get(0).doc()), "higher tf ranks first");
      equal(1.0, hits.get(0).score(), "normalized top score");
      check(hits.get(1).score() < 1.0 && hits.get(1).score() > 0, "score in (0,1)");
      // "whale" (df=2) is rarer than "ship" (df=4): a whale doc must beat a ship-only doc
      List<Searcher.Hit> mixed = Searcher.search(idx, List.of("whale", "ship"), List.of(), 10);
      check(!idx.docs().id(mixed.get(0).doc()).equals("c"), "rare term dominates");
      equal(List.of("whale", "ship"), mixed.get(0).matchedTerms(), "matched terms");
    }
  }

  @Test
  void expansionTermsWeighLessThanUserTerms() throws Exception {
    try (TestCorpus c = new TestCorpus(0, 0, 1)) {
      c.write("user.txt", "ocean filler text");
      c.write("expand.txt", "sea filler text");
      c.write("none.txt", "filler text only");
      InvertedIndex idx = build(c);
      List<Searcher.Hit> hits = Searcher.search(idx, List.of("ocean"), List.of("sea"), 10);
      equal(2, hits.size(), "expansion adds recall");
      equal("user", idx.docs().id(hits.get(0).doc()), "user term ranks above expansion term");
      check(Searcher.search(idx, List.of("absent"), List.of(), 10).isEmpty(), "no match");
      check(Searcher.search(idx, List.of(), List.of(), 10).isEmpty(), "empty query");
    }
  }

  @Test
  void tiesAreBrokenByDocumentId() throws Exception {
    try (TestCorpus c = new TestCorpus(0, 0, 1)) {
      for (String n : new String[] {"d3", "d1", "d2"}) {
        c.write(n + ".txt", "lantern words here");
      }
      InvertedIndex idx = build(c);
      List<Searcher.Hit> hits = Searcher.search(idx, List.of("lantern"), List.of(), 2);
      equal("d1", idx.docs().id(hits.get(0).doc()), "first by id");
      equal("d2", idx.docs().id(hits.get(1).doc()), "second by id");
    }
  }

  @Test
  void snippetMatchesWholeWordsOnly() throws Exception {
    Path f = Files.createTempFile("snip", ".txt");
    try {
      String filler = "lorem ipsum dolor ".repeat(30);
      Files.writeString(f, filler + "an unparalleled view. Later the PARALLEL\n\n  universe "
          + filler);
      String s = Snippets.build(f, List.of("parallel"));
      check(s.contains("PARALLEL universe"), "found whole word, whitespace collapsed: " + s);
      check(s.startsWith("… ") && s.endsWith(" …"), "ellipses at cut ends: " + s);
      check(s.length() <= Snippets.WINDOW + 4, "bounded length");
      check(!s.contains("  "), "no double spaces");
      String none = Snippets.build(f, List.of("missing"));
      check(none.startsWith("lorem"), "falls back to document head");
    } finally {
      Files.deleteIfExists(f);
    }
  }

  @Test
  void snippetFindsWordAcrossBlockBoundary() throws Exception {
    Path f = Files.createTempFile("snip", ".txt");
    try {
      int block = 64 * 1024;
      Files.writeString(f, "x ".repeat((block - 3) / 2) + " needle tail");
      check(Snippets.build(f, List.of("needle")).contains("needle"), "found across block");
    } finally {
      Files.deleteIfExists(f);
    }
  }
}
