package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorLocatorTest {
  @Test
  void movesBetweenErrorsAndSkipsWarnings() {
    List<ResultProblem> ps = List.of(new ResultProblem(5, 2, true, "e1"), new ResultProblem(9, 1, false, "w"),
        new ResultProblem(20, 3, true, "e2"));
    assertEquals(0, ErrorLocator.next(ps, 0, true));
    assertEquals(2, ErrorLocator.next(ps, 5, true));
    assertEquals(-1, ErrorLocator.next(ps, 20, true));
    assertEquals(0, ErrorLocator.next(ps, 20, false));
    assertEquals(-1, ErrorLocator.next(ps, 5, false));
  }
}
