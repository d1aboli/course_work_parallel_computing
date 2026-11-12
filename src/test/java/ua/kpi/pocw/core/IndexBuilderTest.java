package ua.kpi.pocw.core;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;
import static ua.kpi.pocw.testing.Assert.throwsType;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import ua.kpi.pocw.testing.Test;
import ua.kpi.pocw.testing.TestCorpus;

/**
 * Correctness and race tests: every parallel build must produce exactly the same index as the
 * single-threaded one, for both builder implementations and many partition counts.
 */
class IndexBuilderTest {
  /** Reference index computed sequentially without any of the production classes. */
  private static Map<String, Map<Integer, Integer>> reference(TestCorpus c) throws Exception {
    Map<String, Map<Integer, Integer>> ref = new HashMap<>();
    var files = Indexer.listFiles(c.dir());
    for (int d = 0; d < files.size(); d++) {
      final int doc = d;
      byte[] data = Files.readAllBytes(files.get(d));
      Tokenizer.tokenize(data, data.length,
          t -> ref.computeIfAbsent(t, k -> new HashMap<>()).merge(doc, 1, Integer::sum));
    }
    return ref;
  }

  private static void assertMatches(Map<String, Map<Integer, Integer>> ref, InvertedIndex idx,
      String label) {
    equal((long) ref.size(), idx.termCount(), label + ": term count");
    for (var e : ref.entrySet()) {
      long[] p = idx.postings(e.getKey());
      check(p != null, label + ": missing term " + e.getKey());
      equal(e.getValue().size(), p.length, label + ": df of " + e.getKey());
      for (int i = 0; i < p.length; i++) {
        if (i > 0) {
          check(PostingList.docId(p[i - 1]) < PostingList.docId(p[i]),
              label + ": postings not strictly sorted for " + e.getKey());
        }
        equal(e.getValue().get(PostingList.docId(p[i])), PostingList.tf(p[i]),
            label + ": tf of " + e.getKey());
      }
    }
  }

  @Test
  void parallelBuildsEqualSequentialReference() throws Exception {
    try (TestCorpus c = new TestCorpus(400, 300, 42)) {
      var ref = reference(c);
      Indexer indexer = new Indexer(c.dir());
      for (String mapper : new String[] {"arena", "hashmap"}) {
        for (String impl : new String[] {"partitioned", "striped"}) {
          for (int threads : new int[] {1, 2, 4, 8, 16}) {
            for (int parts : new int[] {1, 3, 64}) {
              var s = indexer.build(new Indexer.BuildRequest(threads, parts, 7, impl, mapper));
              equal(400, s.documentsIndexed(), "documents");
              assertMatches(ref, indexer.snapshot().index(),
                  mapper + "/" + impl + "/t" + threads + "/p" + parts);
            }
          }
        }
      }
    }
  }

  @Test
  void repeatedHighContentionBuildsAreDeterministic() throws Exception {
    // batch size 1 + one partition maximizes contention on the same structure
    try (TestCorpus c = new TestCorpus(300, 200, 7)) {
      var ref = reference(c);
      Indexer indexer = new Indexer(c.dir());
      for (int run = 0; run < 15; run++) {
        String impl = run % 2 == 0 ? "partitioned" : "striped";
        indexer.build(new Indexer.BuildRequest(12, 1 + run % 2, 1, impl));
        assertMatches(ref, indexer.snapshot().index(), impl + " run " + run);
      }
    }
  }

  @Test
  void arenaMapperHandlesCaseStopWordsAndCollisions() throws Exception {
    try (TestCorpus c = new TestCorpus(0, 0, 1)) {
      // "Aa"/"BB" share String.hashCode; mixed case, stop words, digits, too long runs
      c.write("a.txt", "Aa BB aa bb AA The the THE x 42 42 " + "z".repeat(40) + " Ünïcode");
      c.write("b.txt", "bb\nBB\tbB end");
      var ref = reference(c);
      Indexer indexer = new Indexer(c.dir());
      indexer.build(new Indexer.BuildRequest(1, 2, 1, "partitioned", "arena"));
      assertMatches(ref, indexer.snapshot().index(), "arena");
      equal(3, indexer.snapshot().index().termFrequency("aa", 0), "aa counted 3 times");
      equal(0, indexer.snapshot().index().documentFrequency("the"), "stop word dropped");
    }
  }

  @Test
  void documentIdsDoNotDependOnThreadCount() throws Exception {
    try (TestCorpus c = new TestCorpus(50, 50, 3)) {
      Indexer indexer = new Indexer(c.dir());
      indexer.build(new Indexer.BuildRequest(1, 8, 4, "partitioned"));
      DocumentTable a = indexer.snapshot().index().docs();
      String[] ids1 = new String[a.size()];
      int[] len1 = new int[a.size()];
      for (int i = 0; i < a.size(); i++) {
        ids1[i] = a.id(i);
        len1[i] = a.length(i);
      }
      indexer.build(new Indexer.BuildRequest(8, 8, 4, "partitioned"));
      DocumentTable b = indexer.snapshot().index().docs();
      for (int i = 0; i < b.size(); i++) {
        equal(ids1[i], b.id(i), "id " + i);
        equal(len1[i], b.length(i), "length " + i);
      }
      check(Arrays.stream(ids1).allMatch(id -> id.startsWith("part")), "relative ids");
    }
  }

  @Test
  void rejectsEmptyCorpusAndConcurrentBuilds() throws Exception {
    try (TestCorpus c = new TestCorpus(0, 0, 1)) {
      Indexer indexer = new Indexer(c.dir());
      throwsType(Indexer.EmptyCorpusException.class,
          () -> indexer.build(new Indexer.BuildRequest(2, 4, 4, "partitioned")));
      check(indexer.snapshot() == null, "no snapshot published");
    }
    throwsType(IllegalArgumentException.class, () -> new Indexer.BuildRequest(0, 4, 4, "x"));
    throwsType(IllegalArgumentException.class, () -> IndexBuilder.create("nope", 4));
  }
}
