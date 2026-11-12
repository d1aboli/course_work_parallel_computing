package ua.kpi.pocw.api;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import ua.kpi.pocw.net.HttpException;
import ua.kpi.pocw.net.WebSocket;
import ua.kpi.pocw.util.Json;

/**
 * WebSocket endpoint {@code /ws/search}: streams the search pipeline stage by stage.
 *
 * <p>For every request message {@code {"query": "..."}} the server sends {@code query_expansion},
 * then {@code keyword_search}, then {@code done}. A session may carry several queries in a row;
 * it ends when the client closes it or stays silent for {@value #IDLE_MS} ms. Errors that concern
 * a single request (bad JSON, index not built) are reported as a {@code stage: "error"} message
 * and the session stays open; protocol violations close it with the RFC 6455 code.
 */
public final class WsSearchHandler {
  static final int IDLE_MS = 60_000;

  private final SearchService search;

  public WsSearchHandler(SearchService search) {
    this.search = search;
  }

  public void session(WebSocket ws) throws IOException {
    ws.setReadTimeout(IDLE_MS);
    while (true) {
      String text;
      try {
        text = ws.readText();
      } catch (SocketTimeoutException e) {
        ws.close(WebSocket.CLOSE_NORMAL, "Idle timeout");
        return;
      } catch (WebSocket.CloseException e) {
        ws.close(e.code(), e.getMessage());
        return;
      }
      if (text == null) {
        return; // client closed
      }
      handleMessage(ws, text);
    }
  }

  private void handleMessage(WebSocket ws, String text) throws IOException {
    Map<String, Object> req;
    try {
      req = Json.parseObject(text);
    } catch (Json.ParseException e) {
      sendError(ws, 400, "Invalid JSON: " + e.getMessage());
      return;
    }
    Object q = req.get("query");
    if (!(q instanceof String query) || query.isBlank() || query.length() > 1000) {
      sendError(ws, 400, "Field 'query' must be a non-empty string (max 1000 characters)");
      return;
    }
    boolean expand = !Boolean.FALSE.equals(req.get("expand"));
    long t0 = System.nanoTime();

    SearchService.Expanded ex = search.expand(query, expand, true);
    Map<String, Object> expansion = new LinkedHashMap<>();
    expansion.put("original", query);
    expansion.put("query_terms", ex.terms());
    expansion.put("expanded_terms", ex.expansion().terms());
    expansion.put("source", ex.expansion().source());
    expansion.put("time_ms", ex.expansion().timeMs());
    ws.sendText(Json.stringify(Json.obj("stage", "query_expansion", "data", expansion)));

    SearchService.Results res;
    try {
      res = search.keywordSearch(ex, SearchService.DEFAULT_TOP_K);
    } catch (HttpException e) {
      sendError(ws, e.status(), e.getMessage());
      return;
    }
    ws.sendText(
        Json.stringify(
            Json.obj(
                "stage", "keyword_search",
                "data", Json.obj("results", res.results(), "time_ms", res.timeMs()))));
    ws.sendText(
        Json.stringify(
            Json.obj("stage", "done", "data",
                Json.obj("total_time_ms", (System.nanoTime() - t0) / 1_000_000))));
  }

  private static void sendError(WebSocket ws, int code, String message) throws IOException {
    ws.sendText(
        Json.stringify(Json.obj("stage", "error", "data", Json.obj("code", code, "message",
            message))));
  }
}
