package net.aftergit.merge.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The corpus outcome per repository and in total: target/corpus/report.md and report.json. */
final class CorpusReport {
  /** {@code note}: why the repository was skipped or stopped early, else empty. */
  record Section(String repo, String note, CorpusStats stats, List<FileResult> deviating, List<FileResult> failed) {}

  private static final int LISTED = 20;
  private final List<Section> sections = new ArrayList<>();

  void add(Section s) {
    sections.add(s);
  }

  void skip(String repo, String note) {
    sections.add(new Section(repo, note, new CorpusStats(), List.of(), List.of()));
  }

  List<Section> sections() {
    return List.copyOf(sections);
  }

  CorpusStats total() {
    CorpusStats t = new CorpusStats();
    sections.forEach(s -> t.addAll(s.stats()));
    return t;
  }

  /** Spec §10.3: after the alignment, the clean parts of every result equal git's and no auto-resolved block makes up
   * a compound token; an unchecked file, a failed Java check and a merge git could not replay fail too (a merge
   * skipped for missing file contents does not). */
  boolean passed() {
    CorpusStats t = total();
    return t.deviations == 0 && t.madeUpTokens == 0 && t.errors == 0 && t.javaFailures == 0 && t.failedMerges == 0;
  }

  String markdown() {
    StringBuilder sb = new StringBuilder("# AfterGit merge corpus\n\n");
    sb.append(passed() ? "Passed: the clean parts of every result equal git's and no block has a made-up token.\n\n"
        : "FAILED: clean parts differ from git's, blocks have made-up tokens or files, Java checks or merges failed "
            + "(see Deviations and Failures).\n\n");
    sb.append("| Repository | Merges (conflicted / failed) | Files (skipped / errors) | Conflicts | Auto-resolved "
        + "| Fully resolved files | Java without new errors (checker failures) | Deviations | Made-up tokens | Fallbacks "
        + "| Same as the commit | Note |\n");
    sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (Section s : sections) {
      row(sb, s.repo(), s.stats(), s.note());
    }
    row(sb, "**total**", total(), "");
    sb.append("\nAuto-resolved: of the conflicts git left. Java without new errors: fully resolved Java files that javac "
        + "compiles alone without an error that neither ours nor theirs has (no project classpath). Made-up tokens: "
        + "auto-resolved blocks with a compound token (letters and digits joined by '.', '-' or ':', such as a "
        + "version) that none of the block's base, ours and theirs has; it must be 0. Same as the "
        + "commit: fully resolved files equal to what the merge commit recorded (informational).\n");
    CorpusStats total = total();
    sb.append("\n## Resolvers\n\n| Resolver | Blocks resolved | Fully resolved Java files with new errors |\n");
    sb.append("|---|---|---|\n");
    total.resolvedBy.forEach((id, n) -> sb.append("| ").append(id).append(" | ").append(n).append(" | ")
        .append(total.notCompilingBy.getOrDefault(id, 0)).append(" |\n"));
    sb.append("\nBlocks resolved: the conflicts each resolver resolved (its method). Files with new errors: fully "
        + "resolved Java files javac finds an error in that neither ours nor theirs has, counted for every resolver "
        + "that resolved a block of the file. Java members are located with the JDK parser here, an approximation of "
        + "the resolver's JDT parser.\n");
    sb.append("\n## Deviations\n\n");
    for (Section s : sections) {
      for (FileResult r : s.deviating().subList(0, Math.min(LISTED, s.deviating().size()))) {
        sb.append("- ").append(s.repo()).append(' ').append(r.merge()).append(' ').append(r.path()).append(": ")
            .append(detail(r)).append('\n');
      }
    }
    sb.append("\n## Failures\n\n");
    for (Section s : sections) {
      for (FileResult r : s.failed().subList(0, Math.min(LISTED, s.failed().size()))) {
        sb.append("- ").append(s.repo()).append(' ').append(r.merge()).append(' ').append(r.path()).append(": ")
            .append(r.error()).append('\n');
      }
    }
    return sb.toString();
  }

  /** The first deviation and the first made-up token of a deviating file. */
  private static String detail(FileResult r) {
    String madeUp = r.madeUpTokens() > 0 ? "made-up token " + r.firstMadeUp() : null;
    if (r.firstDeviation() == null) {
      return madeUp;
    }
    return madeUp == null ? r.firstDeviation() : r.firstDeviation() + "; " + madeUp;
  }

  private static void row(StringBuilder sb, String name, CorpusStats s, String note) {
    sb.append("| ").append(name)
        .append(" | ").append(s.merges).append(" (").append(s.conflictedMerges).append(" / ").append(s.failedMerges).append(')')
        .append(" | ").append(s.files).append(" (").append(s.skippedFiles).append(" / ").append(s.errors).append(')')
        .append(" | ").append(s.conflicts)
        .append(" | ").append(s.autoResolved).append(" (").append(CorpusStats.percent(s.autoResolved, s.conflicts)).append(')')
        .append(" | ").append(s.fullyResolved).append(" (").append(CorpusStats.percent(s.fullyResolved, s.files)).append(')')
        .append(" | ").append(s.javaNoNewErrors).append(" / ").append(s.javaChecked).append(" (")
        .append(CorpusStats.percent(s.javaNoNewErrors, s.javaChecked)).append(", ").append(s.javaFailures)
        .append(" failed)")
        .append(" | ").append(s.deviations)
        .append(" | ").append(s.madeUpTokens)
        .append(" | ").append(s.fallbacks)
        .append(" | ").append(s.matchesCommit).append(" / ").append(s.committedKnown)
        .append(" | ").append(note.replace("|", "/")).append(" |\n");
  }

  String json() {
    StringBuilder sb = new StringBuilder("{\"passed\":").append(passed()).append(",\"total\":");
    stats(sb, total());
    sb.append(",\"repositories\":[");
    for (int i = 0; i < sections.size(); i++) {
      Section s = sections.get(i);
      sb.append(i == 0 ? "" : ",").append("{\"name\":").append(quote(s.repo())).append(",\"note\":")
          .append(quote(s.note())).append(",\"stats\":");
      stats(sb, s.stats());
      sb.append(",\"deviations\":");
      files(sb, s.deviating(), true);
      sb.append(",\"failures\":");
      files(sb, s.failed(), false);
      sb.append('}');
    }
    return sb.append("]}\n").toString();
  }

  private static void stats(StringBuilder sb, CorpusStats s) {
    sb.append("{\"merges\":").append(s.merges).append(",\"conflictedMerges\":").append(s.conflictedMerges)
        .append(",\"failedMerges\":").append(s.failedMerges).append(",\"files\":").append(s.files)
        .append(",\"skippedFiles\":").append(s.skippedFiles).append(",\"errors\":").append(s.errors)
        .append(",\"conflicts\":").append(s.conflicts).append(",\"autoResolved\":").append(s.autoResolved)
        .append(",\"fullyResolved\":").append(s.fullyResolved).append(",\"javaChecked\":").append(s.javaChecked)
        .append(",\"javaNoNewErrors\":").append(s.javaNoNewErrors)
        .append(",\"javaFailures\":").append(s.javaFailures).append(",\"deviations\":").append(s.deviations)
        .append(",\"madeUpTokens\":").append(s.madeUpTokens).append(",\"deviatingFiles\":").append(s.deviatingFiles).append(",\"fallbacks\":").append(s.fallbacks)
        .append(",\"committedKnown\":").append(s.committedKnown).append(",\"matchesCommit\":").append(s.matchesCommit)
        .append(",\"resolvedBy\":").append(counts(s.resolvedBy))
        .append(",\"notCompilingBy\":").append(counts(s.notCompilingBy))
        .append('}');
  }

  private static String counts(Map<String, Integer> m) {
    StringBuilder sb = new StringBuilder("{");
    m.forEach((id, n) -> sb.append(sb.length() > 1 ? "," : "").append(quote(id)).append(':').append(n));
    return sb.append('}').toString();
  }

  private static void files(StringBuilder sb, List<FileResult> list, boolean deviation) {
    sb.append('[');
    for (int i = 0; i < Math.min(LISTED, list.size()); i++) {
      FileResult r = list.get(i);
      sb.append(i == 0 ? "" : ",").append("{\"merge\":").append(quote(r.merge())).append(",\"path\":")
          .append(quote(r.path())).append(",\"detail\":").append(quote(deviation ? detail(r) : r.error()))
          .append('}');
    }
    sb.append(']');
  }

  static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  void write(Path dir) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("report.md"), markdown(), StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("report.json"), json(), StandardCharsets.UTF_8);
  }
}
