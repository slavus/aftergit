package net.aftergit.merge.ai;

import java.util.Locale;

/** Whether an endpoint stays on this machine (design §4); names are never looked up (Ruling 15 of plan 6b). */
public final class Loopback {
  private Loopback() {}

  public static boolean isLoopback(String host) {
    if (host == null || host.isEmpty()) {
      return false;
    }
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    if (h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) {
      return true;
    }
    String[] parts = h.split("\\.", -1);
    if (parts.length != 4 || !parts[0].equals("127")) {
      return false;
    }
    for (String part : parts) {
      if (!part.matches("\\d{1,3}") || Integer.parseInt(part) > 255) {
        return false;
      }
    }
    return true;
  }
}
