package ua.kpi.pocw.net;

import static ua.kpi.pocw.testing.Assert.equal;

import ua.kpi.pocw.testing.Test;

class WebSocketTest {
  @Test
  void acceptKeyMatchesRfc6455Example() {
    // RFC 6455, section 1.3
    equal("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
        "Sec-WebSocket-Accept");
  }
}
