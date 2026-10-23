package net.aftergit.merge.mcp.protocol;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP sessions by id; the least recently used goes when there are more than {@code max}. */
final class Sessions {
  record Session(String id, String client, String protocolVersion) {}

  private final int max;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Session> map;

  Sessions(int max) {
    this.max = max;
    this.map = new LinkedHashMap<>(16, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
        return size() > Sessions.this.max;
      }
    };
  }

  synchronized Session create(String client, String protocolVersion) {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    Session s = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), client, protocolVersion);
    map.put(s.id(), s);
    return s;
  }

  synchronized Session get(String id) {
    return id == null ? null : map.get(id);
  }

  synchronized boolean remove(String id) {
    return id != null && map.remove(id) != null;
  }

  synchronized void clear() {
    map.clear();
  }

  synchronized int size() {
    return map.size();
  }
}
