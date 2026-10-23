package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SharedResultTest {
  @Test
  void refusesWhatTheBufferCannotHoldLineForLine() {
    assertNull(SharedResult.refusal("a\nb\n", false));
    assertEquals(Messages.reasonCarriageReturn, SharedResult.refusal("a\rb\n", false));
    assertEquals(Messages.reasonByteOrderMark, SharedResult.refusal("\uFEFFa\n", false));
    assertEquals(Messages.reasonUnsavedEditor, SharedResult.refusal("a\n", true));
  }
}
