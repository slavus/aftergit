package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import org.junit.jupiter.api.Test;

class ImportsResolverTest {
  static final String BASE = "import a.A;\n";
  static final String OURS = "import a.A;\nimport b.B;\n";
  static final String THEIRS = "import a.A;\nimport c.C;\n";

  @Test
  void anImportBlockIsAutoResolvedForReview() {
    MergeModel m = ResolverPipelineTest.inRegion(OURS, BASE, THEIRS, Region.IMPORTS);
    assertEquals(new ResolveReport(1, 0), ResolverPipeline.defaults(false).run(m));
    MergeBlock b = m.blocks().get(0);
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(Strategy.IMPORTS, b.strategy());
    assertEquals("imports", b.resolvedBy());
    assertTrue(b.needsReview());
    assertEquals("import a.A;\nimport b.B;\nimport c.C;\n", m.document().chunk(0, m.document().lineCount()));
  }

  @Test
  void revertGivesTheBaseImportsBack() {
    MergeModel m = ResolverPipelineTest.inRegion(OURS, BASE, THEIRS, Region.IMPORTS);
    ResolverPipeline.defaults(false).run(m);
    m.revert(0);
    assertEquals(BASE, m.document().chunk(0, m.document().lineCount()));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
  }

  @Test
  void theSameTextInTheBodyIsNotAnImportBlock() {
    MergeModel m = ResolverPipelineTest.inRegion(OURS, BASE, THEIRS, Region.BODY);
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(true).run(m), "add/add in the body stays");
  }

  @Test
  void anImportBlockThatIsNotOnlyImportsStaysUnresolved() {
    MergeModel m = ResolverPipelineTest.inRegion("import a.A;\nint x;\n", BASE, THEIRS, Region.IMPORTS);
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(false).run(m));
  }

  @Test
  void anEmptyBaseIsResolvedToTheUnionOfTheImports() {
    MergeModel m = ResolverPipelineTest.inRegion("import a.A;\n", "", "import b.B;\n", Region.IMPORTS);
    assertEquals(new ResolveReport(1, 0), ResolverPipeline.defaults(false).run(m));
    assertEquals("import a.A;\nimport b.B;\n", m.document().chunk(0, m.document().lineCount()));
  }

  @Test
  void anEmptySideIsResolvedWithTheOtherSidesAdditions() {
    MergeModel m = ResolverPipelineTest.inRegion("", BASE, THEIRS, Region.IMPORTS);
    assertEquals(new ResolveReport(1, 0), ResolverPipeline.defaults(false).run(m));
    assertEquals("import c.C;\n", m.document().chunk(0, m.document().lineCount()));
  }
}
