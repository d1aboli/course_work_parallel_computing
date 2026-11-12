package ua.kpi.pocw;

import ua.kpi.pocw.core.Indexer;

/** Entry point of the search server. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    Config cfg = Config.fromEnv();
    App app = App.start(cfg);
    System.out.printf(
        "search server listening on :%d (data=%s, model=%s at %s, http threads=%d, cpus=%d)%n",
        app.port(), cfg.dataDir(), cfg.ollamaModel(), cfg.ollamaUrl(), cfg.httpThreads(),
        Runtime.getRuntime().availableProcessors());

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("shutting down...");
      try {
        app.stop(8_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "shutdown"));

    if (cfg.autoIndexThreads() > 0) {
      Thread t = new Thread(() -> {
        try {
          Indexer.BuildStats s = app.indexer().build(new Indexer.BuildRequest(
              cfg.autoIndexThreads(), cfg.defaultPartitions(), cfg.defaultBatchSize(),
              "partitioned"));
          System.out.printf("auto-index: %d documents in %d ms%n", s.documentsIndexed(),
              s.timeMs());
        } catch (Exception e) {
          System.err.println("auto-index failed: " + e);
        }
      }, "auto-index");
      t.setDaemon(true);
      t.start();
    }
  }
}
