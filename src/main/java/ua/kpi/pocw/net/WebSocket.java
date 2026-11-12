package ua.kpi.pocw.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server side of the WebSocket protocol (RFC 6455), written on top of a plain socket.
 *
 * <p>Supported: opening handshake, text messages including fragmented ones (continuation frames),
 * 7/16/64-bit payload lengths, client masking, ping/pong and the closing handshake. Binary
 * messages are refused with 1003, oversized messages with 1009, invalid UTF-8 with 1007 and other
 * protocol violations with 1002. Writes are serialized by a lock so that a pong sent while a
 * message is being written cannot interleave with it.
 */
public final class WebSocket implements AutoCloseable {
  public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  public static final int MAX_MESSAGE = 64 * 1024;

  public static final int CLOSE_NORMAL = 1000;
  public static final int CLOSE_GOING_AWAY = 1001;
  public static final int CLOSE_PROTOCOL = 1002;
  public static final int CLOSE_UNSUPPORTED = 1003;
  public static final int CLOSE_BAD_DATA = 1007;
  public static final int CLOSE_POLICY = 1008;
  public static final int CLOSE_TOO_BIG = 1009;
  public static final int CLOSE_INTERNAL = 1011;

  private static final int OP_CONT = 0x0;
  private static final int OP_TEXT = 0x1;
  private static final int OP_BINARY = 0x2;
  private static final int OP_CLOSE = 0x8;
  private static final int OP_PING = 0x9;
  private static final int OP_PONG = 0xA;

  /** Raised when the connection must be closed with a given code. */
  public static final class CloseException extends IOException {
    private final int code;

    public CloseException(int code, String reason) {
      super(reason);
      this.code = code;
    }

    public int code() {
      return code;
    }
  }

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final ReentrantLock writeLock = new ReentrantLock();
  private boolean closeSent;

  private WebSocket(Socket socket, InputStream in, OutputStream out) {
    this.socket = socket;
    this.in = in;
    this.out = out;
  }

  /** Computes {@code Sec-WebSocket-Accept} = Base64(SHA-1(key + GUID)). */
  public static String acceptKey(String key) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
      return Base64.getEncoder().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Validates the upgrade request and writes the 101 response. Throws {@link HttpException} with
   * the status to send if the request is not an acceptable handshake.
   */
  public static WebSocket accept(HttpRequest req, Socket socket, InputStream in, OutputStream out)
      throws IOException {
    if (!req.method().equals("GET")) {
      throw new HttpException(405, "WebSocket handshake must use GET");
    }
    String connection = req.header("connection");
    if (connection == null || !connection.toLowerCase().contains("upgrade")) {
      throw new HttpException(400, "Missing 'Connection: Upgrade'");
    }
    String key = req.header("sec-websocket-key");
    if (key == null || decodedLength(key) != 16) {
      throw new HttpException(400, "Bad Sec-WebSocket-Key");
    }
    if (!"13".equals(req.header("sec-websocket-version"))) {
      throw new HttpException(426, "Unsupported WebSocket version");
    }
    String resp =
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + acceptKey(key)
            + "\r\n\r\n";
    out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    return new WebSocket(socket, in, out);
  }

  private static int decodedLength(String key) {
    try {
      return Base64.getDecoder().decode(key.trim()).length;
    } catch (IllegalArgumentException e) {
      return -1;
    }
  }

  public void setReadTimeout(int millis) throws IOException {
    socket.setSoTimeout(millis);
  }

  /**
   * Reads the next text message, transparently answering pings. Returns {@code null} when the
   * client closes the connection (the closing handshake is completed).
   */
  public String readText() throws IOException {
    ByteArrayOutputStream message = null;
    while (true) {
      int b0 = readByte();
      int b1 = readByte();
      boolean fin = (b0 & 0x80) != 0;
      if ((b0 & 0x70) != 0) {
        throw new CloseException(CLOSE_PROTOCOL, "Reserved bits set");
      }
      int opcode = b0 & 0x0F;
      if ((b1 & 0x80) == 0) {
        throw new CloseException(CLOSE_PROTOCOL, "Client frames must be masked");
      }
      long len = b1 & 0x7F;
      if (len == 126) {
        len = (readByte() << 8) | readByte();
      } else if (len == 127) {
        len = 0;
        for (int i = 0; i < 8; i++) {
          len = (len << 8) | readByte();
        }
        if (len < 0) {
          throw new CloseException(CLOSE_PROTOCOL, "Bad length");
        }
      }
      boolean control = (opcode & 0x8) != 0;
      if (control && (len > 125 || !fin)) {
        throw new CloseException(CLOSE_PROTOCOL, "Bad control frame");
      }
      long buffered = message == null ? 0 : message.size();
      if (!control && buffered + len > MAX_MESSAGE) {
        throw new CloseException(CLOSE_TOO_BIG, "Message too big");
      }
      byte[] mask = in.readNBytes(4);
      byte[] payload = in.readNBytes((int) len);
      if (mask.length != 4 || payload.length != len) {
        throw new IOException("Connection closed mid-frame");
      }
      for (int i = 0; i < payload.length; i++) {
        payload[i] ^= mask[i & 3];
      }
      switch (opcode) {
        case OP_PING -> sendFrame(OP_PONG, payload);
        case OP_PONG -> {
          // unsolicited pong: ignore
        }
        case OP_CLOSE -> {
          int code = CLOSE_NORMAL;
          if (payload.length == 1) {
            throw new CloseException(CLOSE_PROTOCOL, "Bad close payload");
          }
          if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
          }
          if (!closeSent) {
            sendClose(validCloseCode(code) ? CLOSE_NORMAL : CLOSE_PROTOCOL, "");
          }
          return null;
        }
        case OP_TEXT, OP_BINARY -> {
          if (message != null) {
            throw new CloseException(CLOSE_PROTOCOL, "New message before previous finished");
          }
          if (opcode == OP_BINARY) {
            throw new CloseException(CLOSE_UNSUPPORTED, "Binary messages are not supported");
          }
          message = new ByteArrayOutputStream();
          message.write(payload);
          if (fin) {
            return decodeUtf8(message.toByteArray());
          }
        }
        case OP_CONT -> {
          if (message == null) {
            throw new CloseException(CLOSE_PROTOCOL, "Continuation without a started message");
          }
          message.write(payload);
          if (fin) {
            return decodeUtf8(message.toByteArray());
          }
        }
        default -> throw new CloseException(CLOSE_PROTOCOL, "Unknown opcode " + opcode);
      }
    }
  }

  private static boolean validCloseCode(int code) {
    return (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1011)
        || (code >= 3000 && code <= 4999);
  }

  private static String decodeUtf8(byte[] data) throws CloseException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException e) {
      throw new CloseException(CLOSE_BAD_DATA, "Invalid UTF-8");
    }
  }

  private int readByte() throws IOException {
    int b = in.read();
    if (b < 0) {
      throw new IOException("Connection closed");
    }
    return b;
  }

  public void sendText(String text) throws IOException {
    sendFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
  }

  private void sendFrame(int opcode, byte[] payload) throws IOException {
    writeLock.lock();
    try {
      int len = payload.length;
      byte[] header;
      if (len < 126) {
        header = new byte[] {(byte) (0x80 | opcode), (byte) len};
      } else if (len <= 0xFFFF) {
        header = new byte[] {(byte) (0x80 | opcode), 126, (byte) (len >>> 8), (byte) len};
      } else {
        header = new byte[10];
        header[0] = (byte) (0x80 | opcode);
        header[1] = 127;
        for (int i = 0; i < 8; i++) {
          header[9 - i] = (byte) ((long) len >>> (8 * i));
        }
      }
      out.write(header);
      out.write(payload);
      out.flush();
    } finally {
      writeLock.unlock();
    }
  }

  private void sendClose(int code, String reason) throws IOException {
    byte[] r = reason.getBytes(StandardCharsets.UTF_8);
    int n = Math.min(r.length, 123);
    byte[] payload = new byte[2 + n];
    payload[0] = (byte) (code >>> 8);
    payload[1] = (byte) code;
    System.arraycopy(r, 0, payload, 2, n);
    closeSent = true;
    sendFrame(OP_CLOSE, payload);
  }

  /**
   * Sends a close frame, waits up to one second for the client's close frame and closes the
   * socket.
   */
  public void close(int code, String reason) {
    try {
      if (!closeSent) {
        sendClose(code, reason);
        socket.setSoTimeout(1000);
        try {
          while (readText() != null) {
            // discard data frames until the peer acknowledges the close
          }
        } catch (SocketTimeoutException | CloseException ignored) {
          // peer did not answer in time or misbehaved; close anyway
        }
      }
    } catch (IOException ignored) {
      // connection already broken
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
        // nothing to do
      }
    }
  }

  @Override
  public void close() {
    close(CLOSE_NORMAL, "");
  }
}
