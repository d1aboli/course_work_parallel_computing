package ua.kpi.pocw.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import ua.kpi.pocw.util.Json;

/**
 * Thin client for the Ollama REST API ({@code /api/generate} and {@code /api/tags}).
 *
 * <p>Uses one shared {@link HttpClient}, which is thread-safe and pools connections, with
 * proxying disabled so that a corporate proxy configured in the JVM does not intercept calls to
 * the sibling container.
 */
public final class OllamaClient {
  private final URI base;
  private final String model;
  private final HttpClient http;

  public OllamaClient(String baseUrl, String model) {
    this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.model = model;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(HttpClient.Builder.NO_PROXY)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  public String model() {
    return model;
  }

  /** Returns {@code true} if the server answers {@code GET /api/tags} within the timeout. */
  public boolean isAvailable(Duration timeout) {
    HttpRequest req =
        HttpRequest.newBuilder(base.resolve("api/tags")).timeout(timeout).GET().build();
    try {
      return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Calls {@code /api/generate} without streaming and with JSON output; returns the model text.
   */
  public String generate(String prompt, Duration timeout) throws IOException, InterruptedException {
    Map<String, Object> body =
        Json.obj(
            "model", model,
            "prompt", prompt,
            "stream", false,
            "format", "json",
            "keep_alive", "30m",
            "options", Json.obj("temperature", 0.2, "num_predict", 128, "repeat_penalty", 1.1));
    HttpRequest req =
        HttpRequest.newBuilder(base.resolve("api/generate"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
            .build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IOException("Ollama returned HTTP " + resp.statusCode() + ": " + resp.body());
    }
    Object response = Json.parseObject(resp.body()).get("response");
    if (!(response instanceof String s)) {
      throw new IOException("Ollama response has no 'response' field");
    }
    return s;
  }
}
