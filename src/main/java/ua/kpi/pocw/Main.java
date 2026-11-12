package ua.kpi.pocw;

import java.time.Duration;
import ua.kpi.pocw.api.Router;
import ua.kpi.pocw.api.SearchService;
import ua.kpi.pocw.api.WsSearchHandler;
import ua.kpi.pocw.core.Indexer;
import ua.kpi.pocw.llm.OllamaClient;
import ua.kpi.pocw.llm.QueryExpander;
import ua.kpi.pocw.net.HttpServer;

/** Entry point of the search server. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    Config cfg = Config.fromEnv();
    Indexer indexer = new Indexer(cfg.dataDir());
    OllamaClient ollama = new OllamaClient(cfg.ollamaUrl(), cfg.ollamaModel());
    QueryExpander expander =
        new QueryExpander(ollama, Duration.ofMillis(cfg.llmTimeoutMs()), cfg.llmConcurrency(), 1024);
    SearchService search = new SearchService(indexer, expander);
    WsSearchHandler ws = new WsSearchHandler(search);

    HttpServer[] holder = new HttpServer[1];
    Router router =
        new Router(
            indexer,
            search,
            ollama,
            cfg.webDir(),
            cfg.defaultPartitions(),
            cfg.defaultBatchSize(),
            new Router.ServerMetrics() {
              @Override
              public long requests() {
                return holder[0].requestCount();
              }

              @Override
              public int openConnections() {
                return holder[0].openConnections();
              }

              @Override
              public int httpThreads() {
                return holder[0].httpThreads();
              }
            });
    HttpServer server =
        new HttpServer(cfg.port(), cfg.httpThreads(), cfg.wsThreads(), router::handle,
            "/ws/search", ws::session);
    holder[0] = server;
    server.start();
    System.out.printf(
        "search server listening on :%d (data=%s, model=%s at %s, http threads=%d, cpus=%d)%n",
        server.port(), cfg.dataDir(), cfg.ollamaModel(), cfg.ollamaUrl(), cfg.httpThreads(),
        Runtime.getRuntime().availableProcessors());

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("shutting down...");
                  try {
                    server.stop(8_000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                },
                "shutdown"));

    if (cfg.autoIndexThreads() > 0) {
      Thread t =
          new Thread(
              () -> {
                try {
                  var s =
                      indexer.build(
                          new Indexer.BuildRequest(cfg.autoIndexThreads(), cfg.defaultPartitions(),
                              cfg.defaultBatchSize(), "partitioned"));
                  System.out.printf("auto-index: %d documents in %d ms%n", s.documentsIndexed(),
                      s.timeMs());
                } catch (Exception e) {
                  System.err.println("auto-index failed: " + e);
                }
              },
              "auto-index");
      t.setDaemon(true);
      t.start();
    }
  }
}
