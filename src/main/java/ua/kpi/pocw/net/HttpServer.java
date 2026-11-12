package ua.kpi.pocw.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import ua.kpi.pocw.core.ThreadPool;

/**
 * Multi-threaded HTTP/1.1 server with persistent connections and WebSocket upgrade.
 *
 * <p>Threading model:
 *
 * <ul>
 *   <li>one <b>acceptor</b> thread runs {@code accept()} and hands every connection to the HTTP
 *       pool;
 *   <li>the <b>HTTP pool</b> (own {@link ThreadPool}) serves a connection for its whole keep-alive
 *       lifetime: read request, call the handler, write the response, repeat; this is where
 *       concurrent clients are processed in parallel;
 *   <li>a WebSocket upgrade moves the socket to the separate <b>WebSocket pool</b>, so long-lived
 *       streaming sessions never occupy HTTP threads.
 * </ul>
 *
 * <p>Graceful stop: the listening socket is closed, idle keep-alive connections are closed, and
 * requests in progress are allowed to finish (bounded wait).
 */
public final class HttpServer {
  /** Application request handler; must be thread-safe. */
  @FunctionalInterface
  public interface Handler {
    HttpResponse handle(HttpRequest request) throws Exception;
  }

  /** WebSocket session handler; runs on a WebSocket pool thread. */
  @FunctionalInterface
  public interface WebSocketHandler {
    void session(WebSocket ws) throws IOException;
  }

  private static final int IDLE_TIMEOUT_MS = 15_000;
  private static final int MAX_REQUESTS_PER_CONNECTION = 10_000;

  private final int port;
  private final Handler handler;
  private final String wsPath;
  private final WebSocketHandler wsHandler;
  private final ThreadPool httpPool;
  private final ThreadPool wsPool;
  private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
  private final LongAdder requests = new LongAdder();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private volatile ServerSocket serverSocket;
  private Thread acceptor;

  private static final class Connection {
    final Socket socket;
    final AtomicBoolean busy = new AtomicBoolean();

    Connection(Socket socket) {
      this.socket = socket;
    }
  }

  public HttpServer(
      int port,
      int httpThreads,
      int wsThreads,
      Handler handler,
      String wsPath,
      WebSocketHandler wsHandler) {
    this.port = port;
    this.handler = handler;
    this.wsPath = wsPath;
    this.wsHandler = wsHandler;
    this.httpPool = new ThreadPool(httpThreads, "http");
    this.wsPool = new ThreadPool(wsThreads, "ws");
  }

  public void start() throws IOException {
    ServerSocket ss = new ServerSocket();
    ss.setReuseAddress(true);
    ss.bind(new InetSocketAddress(port), 1024);
    serverSocket = ss;
    acceptor = new Thread(this::acceptLoop, "acceptor");
    acceptor.start();
  }

  /** Actual bound port (useful when started with port 0 in tests). */
  public int port() {
    return serverSocket.getLocalPort();
  }

  private void acceptLoop() {
    while (!stopping.get()) {
      Socket s;
      try {
        s = serverSocket.accept();
      } catch (IOException e) {
        if (!stopping.get()) {
          System.err.println("[http] accept failed: " + e);
        }
        continue;
      }
      Connection c = new Connection(s);
      connections.add(c);
      try {
        httpPool.execute(() -> serve(c));
      } catch (RejectedExecutionException e) {
        closeQuietly(c);
      }
    }
  }

  private void serve(Connection c) {
    Socket socket = c.socket;
    boolean handedOff = false;
    try {
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(IDLE_TIMEOUT_MS);
      InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
      OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
      for (int served = 0; served < MAX_REQUESTS_PER_CONNECTION && !stopping.get(); served++) {
        HttpRequest req;
        try {
          req = HttpRequest.read(in);
        } catch (HttpException e) {
          HttpResponse.error(e.status(), e.getMessage()).write(out, false);
          return;
        }
        if (req == null) {
          return; // client closed the keep-alive connection
        }
        c.busy.set(true);
        try {
          requests.increment();
          if (req.path().equals(wsPath) && req.isWebSocketUpgrade()) {
            handedOff = upgrade(c, req, in, out);
            return;
          }
          HttpResponse resp = dispatch(req);
          boolean keepAlive =
              req.keepAlive() && !stopping.get() && served + 1 < MAX_REQUESTS_PER_CONNECTION;
          resp.write(out, keepAlive);
          if (!keepAlive) {
            return;
          }
        } finally {
          c.busy.set(false);
        }
      }
    } catch (SocketTimeoutException | SocketException e) {
      // idle keep-alive timeout or peer reset: just close
    } catch (IOException e) {
      System.err.println("[http] I/O error: " + e.getMessage());
    } finally {
      if (!handedOff) {
        closeQuietly(c);
      }
    }
  }

  private HttpResponse dispatch(HttpRequest req) {
    try {
      HttpResponse resp = handler.handle(req);
      return resp != null ? resp : HttpResponse.error(404, "Not found");
    } catch (HttpException e) {
      return HttpResponse.error(e.status(), e.getMessage());
    } catch (Exception e) {
      System.err.println("[http] handler failed on " + req.path() + ": " + e);
      return HttpResponse.error(500, "Internal error: " + e.getClass().getSimpleName());
    }
  }

  private boolean upgrade(Connection c, HttpRequest req, InputStream in, OutputStream out)
      throws IOException {
    WebSocket ws;
    try {
      ws = WebSocket.accept(req, c.socket, in, out);
    } catch (HttpException e) {
      HttpResponse resp = HttpResponse.error(e.status(), e.getMessage());
      if (e.status() == 426) {
        resp.header("Sec-WebSocket-Version", "13");
      }
      resp.write(out, false);
      return false;
    }
    try {
      wsPool.execute(
          () -> {
            try {
              wsHandler.session(ws);
            } catch (IOException e) {
              // client went away
            } finally {
              ws.close(WebSocket.CLOSE_NORMAL, "");
              connections.remove(c);
            }
          });
      return true;
    } catch (RejectedExecutionException e) {
      ws.close(WebSocket.CLOSE_GOING_AWAY, "Server is shutting down");
      return false;
    }
  }

  private void closeQuietly(Connection c) {
    connections.remove(c);
    try {
      c.socket.close();
    } catch (IOException ignored) {
      // already closed
    }
  }

  public long requestCount() {
    return requests.sum();
  }

  public int openConnections() {
    return connections.size();
  }

  public int httpThreads() {
    return httpPool.threadCount();
  }

  /** Stops accepting, closes idle connections and waits up to {@code graceMs} for the rest. */
  public void stop(long graceMs) throws InterruptedException {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    try {
      serverSocket.close();
    } catch (IOException ignored) {
      // closing anyway
    }
    for (Connection c : connections) {
      if (!c.busy.get()) {
        closeQuietly(c); // unblocks threads waiting for the next keep-alive request
      }
    }
    httpPool.shutdown();
    wsPool.shutdown();
    long deadline = System.currentTimeMillis() + graceMs;
    httpPool.awaitTermination(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    if (!wsPool.awaitTermination(Math.max(1, deadline - System.currentTimeMillis()),
        TimeUnit.MILLISECONDS)) {
      connections.forEach(this::closeQuietly);
      wsPool.shutdownNow();
    }
    if (acceptor != null) {
      acceptor.join(1000);
    }
  }
}
