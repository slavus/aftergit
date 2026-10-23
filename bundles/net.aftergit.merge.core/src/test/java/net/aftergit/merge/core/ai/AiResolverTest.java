package net.aftergit.merge.core.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class AiResolverTest {
  @Test
  void aResolverNamesNoDestinationUnlessItSaysOne() {
    AiResolver r = new AiResolver() {
      @Override
      public String name() {
        return "Stand-in model";
      }

      @Override
      public boolean ready() {
        return true;
      }

      @Override
      public boolean remote() {
        return true;
      }

      @Override
      public AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) {
        return null;
      }
    };
    assertEquals("", r.destination());
    assertEquals(AiResolver.DEFAULT_CONTEXT_LINES, r.contextLines());
  }
}
