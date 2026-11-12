package ua.kpi.pocw;

import java.nio.file.Path;

/** Server configuration from environment variables, with defaults for local runs. */
public record Config(
    int port,
    Path dataDir,
    Path webDir,
    String ollamaUrl,
    String ollamaModel,
    int httpThreads,
    int wsThreads,
    int llmConcurrency,
    int llmTimeoutMs,
    int defaultPartitions,
    int defaultBatchSize,
    int autoIndexThreads) {

  public static Config fromEnv() {
    return new Config(
        intEnv("PORT", 8080),
        Path.of(env("DATA_DIR", "data/docs")),
        Path.of(env("WEB_DIR", "web")),
        env("OLLAMA_URL", "http://localhost:11434"),
        env("OLLAMA_MODEL", "qwen2.5:1.5b"),
        intEnv("HTTP_THREADS", 64),
        intEnv("WS_THREADS", 32),
        intEnv("LLM_CONCURRENCY", 2),
        intEnv("LLM_TIMEOUT_MS", 20_000),
        intEnv("INDEX_PARTITIONS", 64),
        intEnv("INDEX_BATCH_SIZE", 32),
        intEnv("AUTO_INDEX_THREADS", 0));
  }

  private static String env(String name, String def) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? def : v.trim();
  }

  private static int intEnv(String name, int def) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      return def;
    }
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Environment variable " + name + " must be an integer");
    }
  }
}
