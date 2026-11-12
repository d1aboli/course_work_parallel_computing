package ua.kpi.pocw.testing;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Raw-socket HTTP/1.1 and WebSocket client for tests: gives full control over keep-alive,
 * malformed requests and WebSocket framing.
 */
public final class RawClient implements AutoCloseable {
  /** Parsed response. */
  public record Response(int status, Map<String, String> headers, String body) {}

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final SecureRandom rnd = new SecureRandom();

  public RawClient(int port) throws IOException {
    socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(10_000);
    in = new BufferedInputStream(socket.getInputStream());
    out = socket.getOutputStream();
  }

  public Response request(String method, String path, String body) throws IOException {
    byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    String head = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
        + (body == null ? "" : "Content-Type: application/json\r\n")
        + "Content-Length: " + b.length + "\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
    out.write(b);
    out.flush();
    return readResponse();
  }

  public void sendRaw(String raw) throws IOException {
    out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  public Response readResponse() throws IOException {
    String status = line();
    Map<String, String> headers = new LinkedHashMap<>();
    String l;
    while (!(l = line()).isEmpty()) {
      int c = l.indexOf(':');
      headers.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
    }
    int len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
    String body = new String(in.readNBytes(len), StandardCharsets.UTF_8);
    return new Response(Integer.parseInt(status.split(" ")[1]), headers, body);
  }

  /** Performs the opening handshake and returns the server's response status. */
  public int upgrade(String path) throws IOException {
    byte[] key = new byte[16];
    rnd.nextBytes(key);
    sendRaw("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
        + "Connection: Upgrade\r\nSec-WebSocket-Key: " + Base64.getEncoder().encodeToString(key)
        + "\r\nSec-WebSocket-Version: 13\r\n\r\n");
    String status = line();
    while (!line().isEmpty()) {
      // skip headers
    }
    return Integer.parseInt(status.split(" ")[1]);
  }

  /** Sends one masked frame. */
  public void sendFrame(int opcode, boolean fin, byte[] payload) throws IOException {
    ByteArrayOutputStream f = new ByteArrayOutputStream();
    f.write((fin ? 0x80 : 0) | opcode);
    int len = payload.length;
    if (len < 126) {
      f.write(0x80 | len);
    } else {
      f.write(0x80 | 126);
      f.write(len >>> 8);
      f.write(len);
    }
    byte[] mask = new byte[4];
    rnd.nextBytes(mask);
    f.write(mask);
    for (int i = 0; i < len; i++) {
      f.write(payload[i] ^ mask[i & 3]);
    }
    out.write(f.toByteArray());
    out.flush();
  }

  public void sendText(String s) throws IOException {
    sendFrame(1, true, s.getBytes(StandardCharsets.UTF_8));
  }

  /** Reads one server frame; returns "opcode:payload" (close payload as code). */
  public String readFrame() throws IOException {
    int b0 = in.read();
    int b1 = in.read();
    if (b0 < 0 || b1 < 0) {
      return "eof";
    }
    int len = b1 & 0x7F;
    if (len == 126) {
      len = (in.read() << 8) | in.read();
    } else if (len == 127) {
      long l = 0;
      for (int i = 0; i < 8; i++) {
        l = (l << 8) | in.read();
      }
      len = (int) l;
    }
    byte[] p = in.readNBytes(len);
    int op = b0 & 0x0F;
    if (op == 8) {
      int code = p.length >= 2 ? ((p[0] & 0xFF) << 8) | (p[1] & 0xFF) : 1005;
      return "close:" + code;
    }
    return op + ":" + new String(p, StandardCharsets.UTF_8);
  }

  private String line() throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != '\n') {
      if (c < 0) {
        throw new IOException("eof");
      }
      if (c != '\r') {
        sb.append((char) c);
      }
    }
    return sb.toString();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
