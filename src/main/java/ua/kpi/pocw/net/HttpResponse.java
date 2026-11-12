package ua.kpi.pocw.net;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import ua.kpi.pocw.util.Json;

/** HTTP response with a fully buffered body. */
public final class HttpResponse {
  private final int status;
  private final Map<String, String> headers = new LinkedHashMap<>();
  private final byte[] body;

  public HttpResponse(int status, String contentType, byte[] body) {
    this.status = status;
    this.body = body;
    if (contentType != null) {
      headers.put("Content-Type", contentType);
    }
  }

  public static HttpResponse json(int status, Object value) {
    return new HttpResponse(
        status,
        "application/json; charset=utf-8",
        Json.stringify(value).getBytes(StandardCharsets.UTF_8));
  }

  public static HttpResponse error(int status, String message) {
    return json(status, Json.obj("status", "error", "error", message));
  }

  public HttpResponse header(String name, String value) {
    headers.put(name, value);
    return this;
  }

  public int status() {
    return status;
  }

  public byte[] body() {
    return body;
  }

  public void write(OutputStream out, boolean keepAlive) throws IOException {
    StringBuilder sb = new StringBuilder(256);
    sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
    for (Map.Entry<String, String> h : headers.entrySet()) {
      sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
    }
    sb.append("Content-Length: ").append(body.length).append("\r\n");
    sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
    out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }

  public static String reason(int status) {
    return switch (status) {
      case 101 -> "Switching Protocols";
      case 200 -> "OK";
      case 204 -> "No Content";
      case 400 -> "Bad Request";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 408 -> "Request Timeout";
      case 409 -> "Conflict";
      case 413 -> "Content Too Large";
      case 415 -> "Unsupported Media Type";
      case 426 -> "Upgrade Required";
      case 431 -> "Request Header Fields Too Large";
      case 500 -> "Internal Server Error";
      case 501 -> "Not Implemented";
      case 503 -> "Service Unavailable";
      default -> "Status";
    };
  }
}
