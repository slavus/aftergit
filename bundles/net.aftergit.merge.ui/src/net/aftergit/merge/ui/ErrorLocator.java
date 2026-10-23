package net.aftergit.merge.ui;

import java.util.List;

/** Next/previous error of the result (spec §6.6); warnings do not count, there is no wrap-around. */
final class ErrorLocator {
  private ErrorLocator() {}

  /** Index into {@code problems} (sorted by offset) of the next/previous error, or -1. */
  static int next(List<ResultProblem> problems, int caret, boolean forward) {
    int found = -1;
    for (int i = 0; i < problems.size(); i++) {
      ResultProblem p = problems.get(i);
      if (!p.error()) {
        continue;
      }
      if (forward && p.offset() > caret) {
        return i;
      }
      if (!forward && p.offset() < caret) {
        found = i;
      }
    }
    return forward ? -1 : found;
  }
}
