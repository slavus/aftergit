package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.ai.AiConsent;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §5: the kept "Do not ask again" answers can be forgotten from the preference page. */
class RemoteConsentsTest {
  IEclipsePreferences node;

  @BeforeEach
  void setUp() throws Exception {
    node = InstanceScope.INSTANCE.getNode(AiConsent.NODE);
    node.clear();
  }

  @AfterEach
  void tearDown() throws Exception {
    node.clear();
  }

  @Test
  void forgettingRemovesEveryKeptDestinationAndNothingElse() throws Exception {
    assertEquals(0, RemoteConsents.count());
    node.putBoolean(AiConsent.PREFIX + "https%3A%2F%2Fapi.anthropic.com%3A443", true);
    node.putBoolean(AiConsent.PREFIX + "resolver%3Aother", true);
    node.put("layout", "ALL");
    assertEquals(2, RemoteConsents.count());
    assertEquals(2, RemoteConsents.forget());
    assertEquals(0, RemoteConsents.count());
    assertNull(node.get(AiConsent.PREFIX + "https%3A%2F%2Fapi.anthropic.com%3A443", null));
    assertEquals("ALL", node.get("layout", null), "the resolver's other settings stay");
    assertEquals(0, RemoteConsents.forget());
  }
}
