package ua.kpi.pocw.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import ua.kpi.pocw.util.Json;

/** Fake Ollama server: answers /api/tags and /api/generate with a configurable model text. */
public final class MockOllama implements AutoCloseable {
  private final HttpServer server;
  private final AtomicInteger generateCalls = new AtomicInteger();
  private final AtomicInteger concurrent = new AtomicInteger();
  private final AtomicInteger maxConcurrent = new AtomicInteger();
  private volatile String modelText = "{\"terms\": [\"sea\", \"waves\", \"sailor\"]}";
  private volatile long delayMs;

  public MockOllama() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/api/tags", ex -> {
      byte[] b = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, b.length);
      try (OutputStream o = ex.getResponseBody()) {
        o.write(b);
      }
    });
    server.createContext("/api/generate", ex -> {
      generateCalls.incrementAndGet();
      int now = concurrent.incrementAndGet();
      maxConcurrent.accumulateAndGet(now, Math::max);
      try {
        ex.getRequestBody().readAllBytes();
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
        byte[] b = Json.stringify(Json.obj("model", "mock", "response", modelText, "done", true))
            .getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) {
          o.write(b);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // client gave up (timeout test)
      } finally {
        concurrent.decrementAndGet();
      }
    });
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void respond(String text) {
    modelText = text;
  }

  public void delay(long ms) {
    delayMs = ms;
  }

  public int generateCalls() {
    return generateCalls.get();
  }

  public int maxConcurrent() {
    return maxConcurrent.get();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
