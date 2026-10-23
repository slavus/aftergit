package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiRequest;
import net.aftergit.merge.core.ai.AiRequests;
import net.aftergit.merge.core.ai.AiResolver;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §1, §2: the extension point, broken contributions and what "ready" means. */
class AiResolverContributionsTest {
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final ILogListener listener = (status, plugin) -> {
    if (status.getMessage().startsWith("The AI resolver")) {
      logged.add(status); // only what AiResolverContributions reports
    }
  };

  @BeforeEach
  void setUp() {
    StandInAiResolver.reset();
    Platform.addLogListener(listener);
  }

  @AfterEach
  void tearDown() {
    Platform.removeLogListener(listener);
    AiResolverContributions.setForTests(null);
    StandInAiResolver.reset();
  }

  @Test
  void theExtensionPointIsDeclaredUnderItsDesignName() {
    assertNotNull(Platform.getExtensionRegistry().getExtensionPoint(AiResolverContributions.POINT));
  }

  @Test
  void theStandInOfTheTestFragmentIsContributedAndNotReadyUntilATestSaysSo() {
    AiResolverContributions.Contribution c = AiResolverContributions.all().stream()
        .filter(x -> x.id().equals(StandInAiResolver.ID)).findFirst().orElseThrow();
    assertEquals("Stand-in", c.label());
    assertInstanceOf(StandInAiResolver.class, c.resolver());
    assertTrue(AiResolverContributions.ready().isEmpty());
    StandInAiResolver.ready = true;
    assertEquals(List.of(c), AiResolverContributions.ready());
    assertEquals("Stand-in model", AiResolverContributions.name(c));
  }

  @Test
  void aContributionIsReadWithItsIdAndLabel() {
    List<AiResolverContributions.Contribution> read = AiResolverContributions.read(new IConfigurationElement[] {
        ResolverContributionsTest.element("m", "Model", StandInAiResolver::new),
        ResolverContributionsTest.element("n", null, StandInAiResolver::new)});
    assertEquals(List.of("m", "n"), read.stream().map(AiResolverContributions.Contribution::id).toList());
    assertEquals("Model", read.get(0).label());
    assertEquals("n", read.get(1).label(), "without a name the id is the label");
    assertTrue(logged.isEmpty());
  }

  @Test
  void brokenContributionsAreLoggedAndLeftOut() {
    List<AiResolverContributions.Contribution> read = AiResolverContributions.read(new IConfigurationElement[] {
        ResolverContributionsTest.element("fails", "F", () -> new CoreException(Status.error("no such class"))),
        ResolverContributionsTest.element("overflows", "O", () -> {
          throw new StackOverflowError();
        }),
        ResolverContributionsTest.element("other", "X", Object::new),
        ResolverContributionsTest.element(null, "N", StandInAiResolver::new),
        ResolverContributionsTest.element("twice", "A", StandInAiResolver::new),
        ResolverContributionsTest.element("twice", "B", StandInAiResolver::new)});
    assertEquals(List.of("twice"), read.stream().map(AiResolverContributions.Contribution::id).toList());
    assertEquals("A", read.get(0).label());
    assertEquals(5, logged.size(), logged.toString());
    for (IStatus s : logged) {
      assertFalse(s.getException() instanceof VirtualMachineError, "never a raw StackOverflowError in the log");
    }
  }

  @Test
  void aResolverWhoseReadyFailsIsNotReadyAndLoggedOnce() {
    AiResolver broken = new AiResolver() {
      @Override
      public String name() {
        throw new IllegalStateException("no name");
      }

      @Override
      public boolean ready() {
        throw new StackOverflowError();
      }

      @Override
      public boolean remote() {
        throw new IllegalStateException("unknown");
      }

      @Override
      public int contextLines() {
        throw new IllegalStateException("unknown");
      }

      @Override
      public AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) {
        throw new IllegalStateException("never asked");
      }
    };
    AiResolverContributions.Contribution c = new AiResolverContributions.Contribution("broken", "Broken", broken);
    AiResolverContributions.setForTests(List.of(c));
    assertTrue(AiResolverContributions.ready().isEmpty());
    assertTrue(AiResolverContributions.ready().isEmpty());
    assertEquals("Broken", AiResolverContributions.name(c));
    assertTrue(AiResolverContributions.remote(c), "unknown counts as remote: AfterGit asks");
    assertEquals(AiResolver.DEFAULT_CONTEXT_LINES, AiResolverContributions.contextLines(c));
    AiResolverContributions.name(c);
    assertEquals(4, logged.size(), "each kind of failure once: " + logged);
    for (IStatus s : logged) {
      assertFalse(s.getException() instanceof VirtualMachineError);
    }
  }

  @Test
  void theContextLinesOfAResolverAreKeptInBounds() {
    AiResolverContributions.Contribution c = new AiResolverContributions.Contribution(StandInAiResolver.ID,
        "Stand-in", new StandInAiResolver());
    StandInAiResolver.contextLines = 50_000;
    assertEquals(AiRequests.MAX_CONTEXT_LINES, AiResolverContributions.contextLines(c));
    StandInAiResolver.contextLines = -3;
    assertEquals(0, AiResolverContributions.contextLines(c));
  }

  @Test
  void aBlankNameFallsBackToTheLabel() {
    AiResolverContributions.Contribution c = new AiResolverContributions.Contribution(StandInAiResolver.ID,
        "Stand-in", new StandInAiResolver());
    StandInAiResolver.name = "  ";
    assertEquals("Stand-in", AiResolverContributions.name(c));
    StandInAiResolver.name = null;
    assertEquals("Stand-in", AiResolverContributions.name(c));
  }
}
