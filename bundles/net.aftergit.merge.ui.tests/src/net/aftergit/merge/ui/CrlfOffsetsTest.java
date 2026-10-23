package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CrlfOffsetsTest {
  @Test
  void shiftsByOnePerEarlierLineForCrlf() {
    assertEquals(1, CrlfOffsets.toDocument("ab\ncd\n", 1, "\r\n"));
    assertEquals(4, CrlfOffsets.toDocument("ab\ncd\n", 3, "\r\n")); // 'c' after "ab\r\n"
    assertEquals(3, CrlfOffsets.toDocument("ab\ncd\n", 3, "\n"));
  }
}
