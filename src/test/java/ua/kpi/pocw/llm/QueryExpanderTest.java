package ua.kpi.pocw.llm;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import ua.kpi.pocw.testing.MockOllama;
import ua.kpi.pocw.testing.Test;

class QueryExpanderTest {
  @Test
  void parsesWellFormedAndBrokenModelOutput() {
    equal(List.of("sea", "waves"), QueryExpander.parseTerms("{\"terms\": [\"sea\", \"waves\"]}"),
        "object");
    equal(List.of("a1", "b2"), QueryExpander.parseTerms("```json\n[\"a1\",\"b2\"]\n```"),
        "fenced array");
    equal(List.of("ship", "boat"),
        QueryExpander.parseTerms("Sure! {\"keywords\": [\"ship\", \"boat\"]} hope it helps"),
        "chatter around JSON");
    equal(List.of("tempest", "gale"),
        QueryExpander.parseTerms("{\"terms\": [\"tempest\", \"gale\", \"hurr"),
        "truncated by num_predict");
  }

  @Test
  void cleansCandidates() {
    List<String> out = QueryExpander.clean(
        List.of("Ocean", "sea", "the", "SEA", "deep blue", "storm", "123", "w"),
        List.of("storm"));
    equal(List.of("ocean", "sea", "deep", "blue"), out, "tokenized, deduped, filtered");
  }

  @Test
  void usesCacheAndFallsBackGracefully() throws Exception {
    try (MockOllama m = new MockOllama()) {
      QueryExpander qe = new QueryExpander(new OllamaClient(m.url(), "mock"),
          Duration.ofSeconds(5), 2, 16);
      QueryExpander.Expansion a = qe.expand("stormy ocean", List.of("stormy", "ocean"));
      equal("llm", a.source(), "first call hits the model");
      equal(List.of("sea", "waves", "sailor"), a.terms(), "terms");
      QueryExpander.Expansion b = qe.expand("stormy ocean", List.of("stormy", "ocean"));
      equal("cache", b.source(), "second call is cached");
      equal(1, m.generateCalls(), "one model call");
      equal("empty", qe.expand("пошук", List.of()).source(), "no terms, no call");
    }
    QueryExpander down = new QueryExpander(new OllamaClient("http://127.0.0.1:1", "x"),
        Duration.ofSeconds(2), 1, 4);
    QueryExpander.Expansion e = down.expand("q", List.of("q1"));
    check(e.degraded() && e.terms().isEmpty(), "unavailable model degrades to no terms");
  }

  @Test
  void enforcesDeadline() throws Exception {
    try (MockOllama m = new MockOllama()) {
      m.delay(3_000);
      QueryExpander qe = new QueryExpander(new OllamaClient(m.url(), "mock"),
          Duration.ofMillis(300), 1, 4);
      long t0 = System.nanoTime();
      QueryExpander.Expansion e = qe.expand("slow query", List.of("slow", "query"));
      long ms = (System.nanoTime() - t0) / 1_000_000;
      equal("timeout", e.source(), "timed out");
      check(ms < 1_500, "returned near the deadline, took " + ms + " ms");
    }
  }

  @Test
  void coalescesIdenticalQueriesAndBoundsConcurrency() throws Exception {
    try (MockOllama m = new MockOllama()) {
      m.delay(300);
      QueryExpander qe = new QueryExpander(new OllamaClient(m.url(), "mock"),
          Duration.ofSeconds(10), 2, 64);
      int n = 12;
      CyclicBarrier barrier = new CyclicBarrier(n);
      List<Thread> ts = new ArrayList<>();
      List<String> sources = java.util.Collections.synchronizedList(new ArrayList<>());
      for (int i = 0; i < n; i++) {
        final String q = "query" + (i % 4); // 4 distinct queries, 3 callers each
        Thread t = new Thread(() -> {
          try {
            barrier.await();
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
          sources.add(qe.expand(q, List.of(q)).source());
        });
        ts.add(t);
        t.start();
      }
      for (Thread t : ts) {
        t.join();
      }
      check(m.generateCalls() <= 4, "coalesced to at most 4 calls, got " + m.generateCalls());
      check(m.maxConcurrent() <= 2, "at most 2 concurrent calls, got " + m.maxConcurrent());
      check(sources.stream().allMatch(s -> s.equals("llm") || s.equals("cache")),
          "all succeeded: " + sources);
    }
  }
}
