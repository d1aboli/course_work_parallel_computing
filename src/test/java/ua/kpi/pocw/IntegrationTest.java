package ua.kpi.pocw;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import ua.kpi.pocw.testing.MockOllama;
import ua.kpi.pocw.testing.RawClient;
import ua.kpi.pocw.testing.Test;
import ua.kpi.pocw.testing.TestCorpus;
import ua.kpi.pocw.util.Json;

/** End-to-end tests of the REST contract and the WebSocket pipeline on a real server. */
class IntegrationTest implements AutoCloseable {
  private final TestCorpus corpus = new TestCorpus(120, 200, 11);
  private final MockOllama ollama;
  private final App app;

  IntegrationTest() throws Exception {
    corpus.write("special/moby.txt", "Call me Ishmael. The whale was white and the sea was calm.");
    ollama = new MockOllama();
    ollama.respond("{\"terms\": [\"sea\", \"ocean\", \"leviathan\", \"whale\"]}");
    app = App.start(new Config(0, corpus.dir(), Path.of("web"), ollama.url(), "mock", 8, 4, 2,
        3000, 16, 8, 0));
  }

  @Override
  public void close() throws Exception {
    app.stop(2000);
    ollama.close();
    corpus.close();
  }

  private RawClient client() throws Exception {
    return new RawClient(app.port());
  }

  @Test
  void healthStatsAndIndexFollowTheContract() throws Exception {
    try (RawClient c = client()) {
      RawClient.Response h = c.request("GET", "/api/health", null);
      equal(200, h.status(), "health status");
      Map<String, Object> hb = Json.parseObject(h.body());
      equal("ok", hb.get("status"), "health body");
      equal(true, hb.get("ollama"), "ollama reachable");

      equal(409, c.request("POST", "/api/search", "{\"query\":\"whale\"}").status(),
          "search before index");
      equal(0L, Json.parseObject(c.request("GET", "/api/stats", null).body()).get("documents"),
          "no documents yet");

      RawClient.Response idx = c.request("POST", "/api/index", "{\"threads\": 4}");
      equal(200, idx.status(), "index status");
      Map<String, Object> ib = Json.parseObject(idx.body());
      equal("ok", ib.get("status"), "index body");
      equal(121L, ib.get("documents_indexed"), "documents_indexed");
      check(ib.get("time_ms") instanceof Long, "time_ms");

      Map<String, Object> st = Json.parseObject(c.request("GET", "/api/stats", null).body());
      equal(121L, st.get("documents"), "stats documents");
      equal(4L, st.get("threads_used"), "threads_used");
      check(st.containsKey("index_build_time_ms"), "index_build_time_ms");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void searchReturnsContractFormatAndExpands() throws Exception {
    try (RawClient c = client()) {
      c.request("POST", "/api/index", "{\"threads\": 2, \"impl\": \"striped\"}");
      RawClient.Response r =
          c.request("POST", "/api/search", "{\"query\":\"white whale\",\"mode\":\"keyword\"}");
      equal(200, r.status(), "search status");
      Map<String, Object> b = Json.parseObject(r.body());
      List<Map<String, Object>> results = (List<Map<String, Object>>) b.get("results");
      equal("special/moby", results.get(0).get("doc_id"), "best doc");
      equal(1.0, results.get(0).get("score"), "top score");
      check(((String) results.get(0).get("snippet")).contains("whale"), "snippet");
      check(b.get("time_ms") instanceof Long, "time_ms");

      Map<String, Object> e = Json.parseObject(c.request("POST", "/api/search",
          "{\"query\":\"whale\",\"mode\":\"keyword\",\"expand\":true}").body());
      equal(List.of("sea", "ocean", "leviathan"), e.get("expanded_terms"),
          "query term removed from expansion");
      equal("llm", e.get("expansion_source"), "source");
    }
  }

  @Test
  void rejectsBadRequests() throws Exception {
    try (RawClient c = client()) {
      c.request("POST", "/api/index", "{\"threads\": 2}");
      equal(400, c.request("POST", "/api/search", "{bad json").status(), "bad json");
      equal(400, c.request("POST", "/api/search", "{\"query\":\"   \"}").status(), "blank");
      equal(400, c.request("POST", "/api/search", "{\"query\":\"x\",\"mode\":\"semantic\"}")
          .status(), "mode");
      equal(400, c.request("POST", "/api/index", "{\"threads\": 0}").status(), "threads=0");
      equal(400, c.request("POST", "/api/index", "{\"threads\": \"many\"}").status(), "threads");
      equal(200, c.request("POST", "/api/index", "{\"threads\": \"3\"}").status(), "string int");
      equal(405, c.request("GET", "/api/search", null).status(), "method");
      equal(404, c.request("GET", "/api/nope", null).status(), "unknown");
      equal(426, c.request("GET", "/ws/search", null).status(), "plain GET on ws path");
      Map<String, Object> cyr = Json.parseObject(
          c.request("POST", "/api/search", "{\"query\":\"кит\"}").body());
      equal(List.of(), cyr.get("results"), "Cyrillic query gives empty result, not an error");
    }
  }

  @Test
  void keepAliveServesManyRequestsOnOneConnection() throws Exception {
    try (RawClient c = client()) {
      for (int i = 0; i < 50; i++) {
        RawClient.Response r = c.request("GET", "/api/health", null);
        equal(200, r.status(), "request " + i);
        equal("keep-alive", r.headers().get("connection"), "connection header");
      }
    }
  }

  @Test
  void concurrentClientsGetConsistentResults() throws Exception {
    try (RawClient c = client()) {
      c.request("POST", "/api/index", "{\"threads\": 4}");
    }
    String expected;
    try (RawClient c = client()) {
      expected = Json.parseObject(c.request("POST", "/api/search",
          "{\"query\":\"dragon castle\"}").body()).get("results").toString();
    }
    AtomicInteger ok = new AtomicInteger();
    List<Thread> ts = new ArrayList<>();
    for (int t = 0; t < 16; t++) {
      Thread th = new Thread(() -> {
        try (RawClient c = client()) {
          for (int i = 0; i < 20; i++) {
            String got = Json.parseObject(c.request("POST", "/api/search",
                "{\"query\":\"dragon castle\"}").body()).get("results").toString();
            if (got.equals(expected)) {
              ok.incrementAndGet();
            }
          }
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      ts.add(th);
      th.start();
    }
    // rebuild while clients are searching: build-then-swap must not disturb them
    try (RawClient c = client()) {
      equal(200, c.request("POST", "/api/index", "{\"threads\": 8}").status(), "rebuild");
    }
    for (Thread th : ts) {
      th.join();
    }
    equal(320, ok.get(), "every concurrent search returned the same results");
  }

  @Test
  void webSocketStreamsAllStages() throws Exception {
    try (RawClient c = client()) {
      c.request("POST", "/api/index", "{\"threads\": 2}");
    }
    try (RawClient ws = client()) {
      equal(101, ws.upgrade("/ws/search"), "handshake");
      for (int round = 0; round < 2; round++) { // two queries on one session
        ws.sendText("{\"query\": \"white whale\"}");
        String s1 = ws.readFrame();
        String s2 = ws.readFrame();
        String s3 = ws.readFrame();
        Map<String, Object> m1 = Json.parseObject(s1.substring(2));
        equal("query_expansion", m1.get("stage"), "stage 1");
        Map<?, ?> d1 = (Map<?, ?>) m1.get("data");
        equal("white whale", d1.get("original"), "original");
        check(((List<?>) d1.get("expanded_terms")).contains("leviathan"), "expanded");
        Map<String, Object> m2 = Json.parseObject(s2.substring(2));
        equal("keyword_search", m2.get("stage"), "stage 2");
        check(!((List<?>) ((Map<?, ?>) m2.get("data")).get("results")).isEmpty(), "results");
        equal("done", Json.parseObject(s3.substring(2)).get("stage"), "stage 3");
      }
      // fragmented message: {"query": "sea"} split in two frames
      ws.sendFrame(1, false, "{\"query\": ".getBytes());
      ws.sendFrame(0, true, "\"sea\"}".getBytes());
      check(ws.readFrame().contains("query_expansion"), "fragmented message reassembled");
      ws.readFrame();
      ws.readFrame();
      ws.sendFrame(9, true, "hi".getBytes());
      equal("10:hi", ws.readFrame(), "pong echoes ping payload");
      ws.sendText("not json");
      check(ws.readFrame().contains("\"stage\":\"error\""), "error stage for bad JSON");
      ws.sendFrame(8, true, new byte[] {0x03, (byte) 0xE8});
      equal("close:1000", ws.readFrame(), "close handshake");
    }
  }

  @Test
  void webSocketProtocolViolationsCloseWithCodes() throws Exception {
    try (RawClient ws = client()) {
      ws.upgrade("/ws/search");
      ws.sendFrame(2, true, new byte[] {1, 2});
      equal("close:1003", ws.readFrame(), "binary refused");
    }
    try (RawClient ws = client()) {
      ws.upgrade("/ws/search");
      ws.sendFrame(1, true, new byte[] {(byte) 0xC3, 0x28});
      equal("close:1007", ws.readFrame(), "invalid UTF-8");
    }
    try (RawClient ws = client()) {
      ws.upgrade("/ws/search");
      ws.sendFrame(0, true, "x".getBytes());
      equal("close:1002", ws.readFrame(), "continuation without start");
    }
  }

  @Test
  void degradesWhenModelIsSlow() throws Exception {
    try (RawClient c = client()) {
      c.request("POST", "/api/index", "{\"threads\": 2}");
      ollama.delay(5000);
      long t0 = System.nanoTime();
      Map<String, Object> b = Json.parseObject(c.request("POST", "/api/search",
          "{\"query\":\"golden harbor\",\"expand\":true}").body());
      long ms = (System.nanoTime() - t0) / 1_000_000;
      ollama.delay(0);
      equal("timeout", b.get("expansion_source"), "timed out");
      check(!((List<?>) b.get("results")).isEmpty(), "search still answered");
      check(ms < 4500, "bounded by LLM timeout, took " + ms);
    }
  }
}
