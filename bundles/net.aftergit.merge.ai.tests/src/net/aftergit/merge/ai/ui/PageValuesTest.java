package net.aftergit.merge.ai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.aftergit.merge.ai.Provider;
import net.aftergit.merge.ai.Settings;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §4, §5: what the fields of the preference page hold before they are saved. */
class PageValuesTest {
  static Settings saved(Provider p) {
    return p == Provider.ANTHROPIC
        ? new Settings(false, p, p.defaultEndpoint(), p.defaultModel(), true, 40, 120)
        : new Settings(false, p, p.defaultEndpoint(), "", false, 40, 120);
  }

  @Test
  void theFieldsStartFromTheSavedValues() {
    PageValues v = new PageValues(Provider.ANTHROPIC, PageValuesTest::saved);
    assertEquals(Provider.ANTHROPIC, v.provider());
    assertFalse(v.enabled());
    assertEquals("https://api.anthropic.com/v1/messages", v.endpoint());
    assertEquals("claude-sonnet-5-5", v.model());
    assertTrue(v.keySet());
    assertNull(v.typedKey());
    assertEquals("40", v.contextLines());
    assertEquals("120", v.timeout());
  }

  @Test
  void switchingTheProviderKeepsWhatWasTypedForEach() {
    PageValues v = new PageValues(Provider.ANTHROPIC, PageValuesTest::saved);
    v.setModel("other-model");
    v.select(Provider.OPENAI_COMPATIBLE);
    assertEquals("http://localhost:11434/v1/chat/completions", v.endpoint());
    assertEquals("", v.model());
    assertFalse(v.keySet());
    v.setModel("llama3.1");
    v.select(Provider.ANTHROPIC);
    assertEquals("other-model", v.model());
    v.select(Provider.OPENAI_COMPATIBLE);
    assertEquals("llama3.1", v.model());
  }

  @Test
  void aTypedKeyCountsAsSetAndRemovingClearsIt() {
    PageValues v = new PageValues(Provider.OPENAI_COMPATIBLE, PageValuesTest::saved);
    assertFalse(v.keySet());
    v.setKey("  test-key ");
    assertTrue(v.keySet());
    assertEquals("test-key", v.typedKey());
    assertEquals(Map.of(Provider.OPENAI_COMPATIBLE, "test-key"), v.typedKeys());
    v.select(Provider.ANTHROPIC);
    v.removeKey();
    assertFalse(v.keySet());
    assertEquals(Set.of(Provider.ANTHROPIC), v.removedKeys());
    v.setKey("");
    assertNull(v.typedKey(), "an empty field types nothing");
  }

  @Test
  void errorsOnlyForWhatMattersNow() {
    PageValues v = new PageValues(Provider.OPENAI_COMPATIBLE, PageValuesTest::saved);
    assertNull(v.error(), "switched off, a missing model is no error yet");
    v.setEnabled(true);
    assertEquals(Messages.noModel, v.error());
    v.setModel("llama3.1");
    assertNull(v.error());
    v.setEndpoint("not a url");
    assertEquals(Messages.badEndpoint, v.error());
    v.setEndpoint("http://localhost:11434/v1/chat/completions");
    v.setContextLines("lots");
    assertEquals(Messages.badContextLines, v.error());
    v.setEnabled(false);
    assertEquals(Messages.badContextLines, v.error(), "a number field is checked even when off");
    v.setContextLines("12");
    v.setTimeout("2");
    assertEquals(Messages.badTimeout, v.error());
    v.setTimeout("30");
    v.select(Provider.ANTHROPIC);
    v.setEnabled(true);
    v.removeKey();
    assertEquals(Messages.noKey, v.error());
  }

  @Test
  void theSettingsCarryTheParsedNumbersAndTheChosenProviderComesLast() {
    PageValues v = new PageValues(Provider.ANTHROPIC, PageValuesTest::saved);
    v.setEnabled(true);
    v.setContextLines(" 12 ");
    v.setTimeout("30");
    Settings s = v.settings();
    assertTrue(s.enabled());
    assertEquals(12, s.contextLines());
    assertEquals(30, s.timeoutSeconds());
    List<Settings> all = v.all();
    assertEquals(Provider.ANTHROPIC, all.get(all.size() - 1).provider(), "saved last, so it is the chosen one");
    assertEquals(2, all.size());
    assertTrue(all.stream().allMatch(Settings::enabled), "one switch for both");
  }

  @Test
  void defaultsPutEveryProviderBackAndSwitchOff() {
    PageValues v = new PageValues(Provider.OPENAI_COMPATIBLE, PageValuesTest::saved);
    v.setEnabled(true);
    v.setModel("x");
    v.defaults();
    assertFalse(v.enabled());
    assertEquals(Provider.ANTHROPIC, v.provider());
    assertEquals("claude-sonnet-5-5", v.model());
    assertEquals("40", v.contextLines());
  }

  @Test
  void aPlainHttpEndpointOffThisComputerIsWarnedAbout() {
    PageValues v = new PageValues(Provider.OPENAI_COMPATIBLE, PageValuesTest::saved);
    v.setEnabled(true);
    v.setModel("test-model");
    assertNull(v.warning(), "http on localhost stays on this computer");
    v.setEndpoint("http://10.0.0.5:11434/v1/chat/completions");
    assertEquals(Messages.plainHttp, v.warning());
    v.setEndpoint("https://10.0.0.5/v1/chat/completions");
    assertNull(v.warning());
    v.setEndpoint("http://127.0.0.1:1234/v1/chat/completions");
    assertNull(v.warning());
    v.setEndpoint("http://[::1]:1234/v1/chat/completions");
    assertNull(v.warning());
    v.setEndpoint("HTTP://models.example/v1/chat/completions");
    assertEquals(Messages.plainHttp, v.warning());
    v.setEnabled(false);
    assertNull(v.warning(), "nothing is sent while it is off");
  }
}
