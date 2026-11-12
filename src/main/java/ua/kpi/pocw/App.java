package ua.kpi.pocw;

import java.io.IOException;
import java.time.Duration;
import ua.kpi.pocw.api.Router;
import ua.kpi.pocw.api.SearchService;
import ua.kpi.pocw.api.WsSearchHandler;
import ua.kpi.pocw.core.Indexer;
import ua.kpi.pocw.llm.OllamaClient;
import ua.kpi.pocw.llm.QueryExpander;
import ua.kpi.pocw.net.HttpServer;

/** Wires all components together; used by {@link Main} and by integration tests. */
public final class App {
  private final Indexer indexer;
  private final HttpServer server;

  private App(Indexer indexer, HttpServer server) {
    this.indexer = indexer;
    this.server = server;
  }

  public static App start(Config cfg) throws IOException {
    Indexer indexer = new Indexer(cfg.dataDir());
    OllamaClient ollama = new OllamaClient(cfg.ollamaUrl(), cfg.ollamaModel());
    QueryExpander expander =
        new QueryExpander(ollama, Duration.ofMillis(cfg.llmTimeoutMs()), cfg.llmConcurrency(),
            1024);
    SearchService search = new SearchService(indexer, expander);
    WsSearchHandler ws = new WsSearchHandler(search);
    HttpServer[] holder = new HttpServer[1];
    Router router =
        new Router(indexer, search, ollama, cfg.webDir(), cfg.defaultPartitions(),
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
    return new App(indexer, server);
  }

  public Indexer indexer() {
    return indexer;
  }

  public int port() {
    return server.port();
  }

  public void stop(long graceMs) throws InterruptedException {
    server.stop(graceMs);
  }
}
