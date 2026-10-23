package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.aftergit.merge.core.ai.AiResolver;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Resolve with AI design §2: the bundle contributes ModelResolver to net.aftergit.merge.aiResolver. The extension
 * point lives in net.aftergit.merge.ui, which this headless runtime lacks, so the contribution is read as text.
 */
class PluginXmlTest {
  static String entry(String name) throws Exception {
    Bundle b = FrameworkUtil.getBundle(ModelResolver.class);
    try (InputStream in = b.getEntry(name).openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void theModelResolverIsContributedAndCanBeCreated() throws Exception {
    String xml = entry("plugin.xml");
    assertTrue(xml.contains("point=\"net.aftergit.merge.aiResolver\""), xml);
    assertTrue(xml.contains("id=\"" + ModelResolver.ID + "\""), xml);
    assertTrue(xml.contains("class=\"" + ModelResolver.class.getName() + "\""), xml);
    assertTrue(entry("plugin.properties").contains("resolver.model.name="));
    Object created = ModelResolver.class.getConstructor().newInstance();
    assertInstanceOf(AiResolver.class, created);
  }
}
