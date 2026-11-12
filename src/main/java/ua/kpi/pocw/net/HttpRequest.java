package ua.kpi.pocw.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed HTTP/1.1 request (RFC 9112) read from a blocking stream.
 *
 * <p>Supports {@code Content-Length} bodies only; chunked request bodies are rejected with 501.
 * Header names are lower-cased. Limits protect the server from oversized input: 16 KiB for the
 * request line plus headers and 1 MiB for the body.
 */
public final class HttpRequest {
  public static final int MAX_HEAD = 16 * 1024;
  public static final int MAX_BODY = 1024 * 1024;

  private final String method;
  private final String path;
  private final String version;
  private final Map<String, String> query;
  private final Map<String, String> headers;
  private final byte[] body;

  public HttpRequest(
      String method,
      String path,
      String version,
      Map<String, String> query,
      Map<String, String> headers,
      byte[] body) {
    this.method = method;
    this.path = path;
    this.version = version;
    this.query = query;
    this.headers = headers;
    this.body = body;
  }

  /**
   * Reads one request. Returns {@code null} if the peer closed the connection before sending
   * anything (normal end of a keep-alive connection).
   */
  public static HttpRequest read(InputStream in) throws IOException {
    String requestLine = readLine(in, true);
    if (requestLine == null) {
      return null;
    }
    while (requestLine.isEmpty()) { // RFC 9112 2.2: ignore leading empty lines
      requestLine = readLine(in, true);
      if (requestLine == null) {
        return null;
      }
    }
    String[] parts = requestLine.split(" ");
    if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
      throw new HttpException(400, "Malformed request line");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    int headBytes = requestLine.length();
    while (true) {
      String line = readLine(in, false);
      if (line == null) {
        throw new HttpException(400, "Unexpected end of headers");
      }
      if (line.isEmpty()) {
        break;
      }
      headBytes += line.length() + 2;
      if (headBytes > MAX_HEAD) {
        throw new HttpException(431, "Request headers too large");
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new HttpException(400, "Malformed header");
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      headers.merge(name, value, (a, b) -> a + ", " + b);
    }
    if (headers.containsKey("transfer-encoding")) {
      throw new HttpException(501, "Chunked request bodies are not supported");
    }
    byte[] body = new byte[0];
    String cl = headers.get("content-length");
    if (cl != null) {
      long len;
      try {
        len = Long.parseLong(cl.trim());
      } catch (NumberFormatException e) {
        throw new HttpException(400, "Bad Content-Length");
      }
      if (len < 0) {
        throw new HttpException(400, "Bad Content-Length");
      }
      if (len > MAX_BODY) {
        throw new HttpException(413, "Request body too large");
      }
      body = in.readNBytes((int) len);
      if (body.length != len) {
        throw new HttpException(400, "Body shorter than Content-Length");
      }
    }
    String target = parts[1];
    String path = target;
    Map<String, String> query = new LinkedHashMap<>();
    int q = target.indexOf('?');
    if (q >= 0) {
      path = target.substring(0, q);
      for (String kv : target.substring(q + 1).split("&")) {
        if (kv.isEmpty()) {
          continue;
        }
        int eq = kv.indexOf('=');
        String k = eq < 0 ? kv : kv.substring(0, eq);
        String v = eq < 0 ? "" : kv.substring(eq + 1);
        try {
          query.put(
              URLDecoder.decode(k, StandardCharsets.UTF_8),
              URLDecoder.decode(v, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
          throw new HttpException(400, "Bad query string");
        }
      }
    }
    return new HttpRequest(
        parts[0],
        path,
        parts[2],
        Collections.unmodifiableMap(query),
        Collections.unmodifiableMap(headers),
        body);
  }

  /** Reads a CRLF (or bare LF) terminated ISO-8859-1 line without the terminator. */
  private static String readLine(InputStream in, boolean eofAllowed) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
    while (true) {
      int b = in.read();
      if (b < 0) {
        if (buf.size() == 0 && eofAllowed) {
          return null;
        }
        throw new HttpException(400, "Unexpected end of stream");
      }
      if (b == '\n') {
        break;
      }
      buf.write(b);
      if (buf.size() > MAX_HEAD) {
        throw new HttpException(431, "Line too long");
      }
    }
    byte[] bytes = buf.toByteArray();
    int len = bytes.length;
    if (len > 0 && bytes[len - 1] == '\r') {
      len--;
    }
    return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public String version() {
    return version;
  }

  public Map<String, String> query() {
    return query;
  }

  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  public Map<String, String> headers() {
    return headers;
  }

  public byte[] body() {
    return body;
  }

  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }

  /** HTTP/1.1 defaults to keep-alive; HTTP/1.0 needs an explicit header. */
  public boolean keepAlive() {
    String c = header("connection");
    String conn = c == null ? "" : c.toLowerCase(Locale.ROOT);
    if (version.equals("HTTP/1.0")) {
      return conn.contains("keep-alive");
    }
    return !conn.contains("close");
  }

  public boolean isWebSocketUpgrade() {
    String upgrade = header("upgrade");
    return upgrade != null && upgrade.equalsIgnoreCase("websocket");
  }
}
