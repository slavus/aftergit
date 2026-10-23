package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Resolve with AI design §4: only a loopback endpoint keeps the data on this machine (Ruling 15). */
class LoopbackTest {
  @Test
  void loopbackLiteralsAndLocalhost() {
    for (String host : new String[] {"localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]",
        "0:0:0:0:0:0:0:1"}) {
      assertTrue(Loopback.isLoopback(host), host);
    }
  }

  @Test
  void everythingElseIsRemoteAndNoNameIsLookedUp() {
    for (String host : new String[] {"api.anthropic.com", "localhost.example", "128.0.0.1", "127.0.0.256",
        "10.0.0.1", "mybox", "::2", "", null, "127.0.0.1.evil.com", "localhost.", "127.1", "0x7f.0.0.1",
        "::ffff:127.0.0.1", "[::1%25lo]", "foo.localhost"}) {
      assertFalse(Loopback.isLoopback(host), String.valueOf(host));
    }
  }
}
