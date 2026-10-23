package net.aftergit.merge.mcp.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.OpenModel;
import net.aftergit.merge.git.agents.OpenModelException;
import net.aftergit.merge.git.agents.OpenModels;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * The conflicts of the workspace for agents (design §3 {@code ConflictResolverService}, agents design §2): list,
 * read, validate, and propose (never save or stage). A file open in AfterGit is read from the resolver's model through
 * {@link OpenModels}; any other is built here. Never holds the store's lock while it calls an open resolver.
 *
 * <p>A service exists only while the MCP server runs, that is while the user allows AI agents; with the switch off
 * nothing creates one, so nothing reads or touches {@code .git/aftergit}. Reading never writes a file or creates
 * {@code .git/aftergit}: the only store changes a read can cause are the ones agents design §2 asks for when stored
 * proposals already exist ({@link ProposalStore#load}: stale ones are deleted and reported once, and an open file's
 * record gets the current working-tree fingerprint).
 */
public final class ConflictResolverService {
  /**
   * Files whose working-tree file or any stage has more bytes than this are refused (agents design §4), and no
   * get_conflict answer carries more than this in UTF-8.
   */
  public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
  public static final int MAX_PROPOSAL_CHARS = 200_000;
  public static final int MAX_RATIONALE_CHARS = 10_000;

  final ProposalStore store;
  final Supplier<OpenModels> openModels;
  final Clock clock;

  /** {@code openModels}: the resolver's service, or null while there is none (no resolver ever opened). */
  public ConflictResolverService(ProposalStore store, Supplier<OpenModels> openModels, Clock clock) {
    this.store = store;
    this.openModels = openModels;
    this.clock = clock;
  }

  /**
   * {@code openBlocks}: null when unknown (not a text conflict, too large, a symbolic link, or the open resolver did
   * not answer); {@code tooLarge}: the file is over {@link #MAX_FILE_BYTES}, so no tool reads it.
   */
  public record FileSummary(ConflictFiles.ConflictFile file, boolean open, Integer openBlocks, int proposals,
      boolean stale, boolean tooLarge) {}

  /**
   * How the texts were read: {@code encoding} is the charset they were decoded with, {@code encodingFallback} true
   * when that is ISO-8859-1 because some input is not valid in the file's own charset (each byte is then one
   * character). {@code lineEnds}: "lf", "crlf" or "mixed", the file's content; the texts always use '\n'.
   */
  public record Format(String encoding, boolean encodingFallback, String lineEnds) {}

  public record Errors(List<LineProblem> ours, List<LineProblem> theirs, List<LineProblem> result) {
    static final Errors UNKNOWN = new Errors(null, null, null);

    public boolean known() {
      return ours != null && theirs != null && result != null;
    }
  }

  public record FileView(ConflictFiles.ConflictFile file, boolean open, ModelView view, List<Proposal> proposals,
      boolean stale, Errors errors, Format format) {}

  /** {@code newErrors}: by block, the errors it brings in; empty when not {@code checked}. */
  public record Validation(boolean checked, List<LineProblem> errors, Map<BlockId, List<LineProblem>> newErrors) {}

  /** {@code fingerprint}: the conflict as it was before anything of it was read. */
  record Snapshot(ConflictFiles.ConflictFile file, Optional<OpenModel> open, ModelView view, JavaChecks java,
      Format format, Fingerprint fingerprint) {}

  interface OpenCall<T> {
    T run() throws OpenModelException;
  }

  public List<FileSummary> listConflicts(String repository) throws AgentException {
    ConflictFiles.Indexes indexes = new ConflictFiles.Indexes();
    List<ConflictFiles.ConflictFile> files = new ArrayList<>();
    for (ConflictFiles.ConflictFile f : ConflictFiles.all(indexes)) {
      if (repository == null || f.inRepository(repository)) {
        files.add(f);
      }
    }
    if (repository != null && files.isEmpty()) {
      throw new AgentException("No repository \"" + repository + "\" with conflicts has projects in the workspace");
    }
    OpenModels om = openModels.get();
    List<FileSummary> out = new ArrayList<>();
    for (ConflictFiles.ConflictFile f : files) {
      Optional<OpenModel> open = om == null ? Optional.empty() : om.find(f.file());
      boolean unreadable = Files.isSymbolicLink(f.repoFile().file().toPath());
      boolean tooLarge = false;
      try {
        tooLarge = OwnModels.tooLarge(f, indexes.of(f.repoFile().repository()));
      } catch (IOException e) {
        unreadable = true; // its sizes are unknown: treat it like a file no tool reads
      }
      Integer blocks = null;
      int proposals = 0;
      boolean stale = false;
      // only a text conflict a tool can read has blocks and proposals; nothing else is read or hashed
      if (f.kind() == ConflictKind.TEXT && !tooLarge && !unreadable) {
        blocks = open.isPresent() ? openBlocks(open.get()) : markerHunks(f);
        ProposalStore.Loaded l = load(f, open.isPresent(), indexes);
        proposals = l.proposals().size();
        stale = l.stale();
      }
      out.add(new FileSummary(f, open.isPresent(), blocks, proposals, stale, tooLarge));
    }
    return out;
  }

  /** The open conflicts of an open file; null when its resolver does not answer (closing, UI busy). */
  private static Integer openBlocks(OpenModel open) {
    try {
      return open.read(MergeModel::unresolvedConflicts);
    } catch (OpenModelException e) {
      return null;
    }
  }

  public FileView getConflict(String path) throws AgentException {
    Snapshot s = snapshot(ConflictFiles.resolve(path));
    ProposalStore.Loaded l = load(s);
    return new FileView(s.file(), s.open().isPresent(), s.view(), l.proposals(), l.stale(),
        errors(s.java(), s.view(), s.view().result()), s.format());
  }

  public Validation validate(String path) throws AgentException {
    Snapshot s = snapshot(ConflictFiles.resolve(path));
    ProposalStore.Loaded l = load(s);
    Map<BlockId, String> replacements = new LinkedHashMap<>();
    for (Proposal p : l.proposals()) {
      s.view().block(p.block()).ifPresent(b -> replacements.put(b.id(), p.text()));
    }
    return validation(s, replacements, null);
  }

  public record Proposed(BlockId block, String status, Validation validation, boolean staleDiscarded) {}

  public record Reverted(BlockId block, boolean removed, boolean reverted) {}

  public record AutoResolved(List<BlockId> proposed, int remaining) {}

  /**
   * Records a proposal for one conflict block and, for an open file, offers it to the resolver (agents design §3):
   * status "applied", "pending", or "recorded" for a file that is not open (or whose editor closed meanwhile).
   * Never saves or stages. {@code source}: the client's name; {@link Proposal#AUTO} belongs to AfterGit's own passes
   * and is refused. When the open editor cannot take the offer now (busy, no answer) the proposal stays stored and
   * the error says so; calling again offers it again.
   */
  public Proposed propose(String path, String block, String text, String rationale, String source)
      throws AgentException {
    if (Proposal.AUTO.equalsIgnoreCase(source)) {
      throw new AgentException("The proposal source \"" + Proposal.AUTO + "\" is reserved for AfterGit");
    }
    int length = Proposal.chunk(text).length();
    if (length > MAX_PROPOSAL_CHARS) {
      throw new AgentException("The text has " + length + " characters (with its final line end); a proposal "
          + "holds at most " + MAX_PROPOSAL_CHARS);
    }
    if (rationale.length() > MAX_RATIONALE_CHARS) {
      throw new AgentException("The rationale has " + rationale.length() + " characters; at most "
          + MAX_RATIONALE_CHARS);
    }
    Snapshot s = snapshot(ConflictFiles.resolve(path));
    ModelView.Block b = block(s, block);
    Proposal p = new Proposal(b.id(), text, rationale, source, clock.instant());
    boolean stale = put(s, p);
    String status = "recorded";
    if (s.open().isPresent()) {
      Offer offer = offer(s, p);
      if (offer == Offer.NOT_FOUND) {
        remove(s.file(), p.block());
        throw new AgentException("Block " + block + " changed in the open resolver; call get_conflict again");
      }
      status = offer == null ? "recorded" : offer == Offer.APPLIED ? "applied" : "pending";
      if (offer != null) {
        shown(s, p);
      }
    }
    return new Proposed(p.block(), status, validation(s, Map.of(b.id(), p.text()), b.id()), stale);
  }

  /**
   * Takes the caller's proposal of a block back: from the open resolver first, then from the store. Proposals of
   * other agents and AfterGit's {@code auto} ones stay ({@code removed} false). When the open editor refuses
   * (busy, no answer) nothing changes and the error asks to try again.
   */
  public Reverted revert(String path, String block, String source) throws AgentException {
    Snapshot s = snapshot(ConflictFiles.resolve(path));
    BlockId id = parse(block);
    BlockId canonical = s.view().block(id).map(ModelView.Block::id).orElse(id);
    ProposalStore.Loaded l = load(s);
    Optional<Proposal> stored = l.proposals().stream()
        .filter(q -> q.block().equals(canonical) && q.source().equals(source)).findFirst();
    if (stored.isEmpty()) {
      return new Reverted(canonical, false, false);
    }
    boolean reverted = false;
    if (s.open().isPresent()) {
      try {
        reverted = s.open().get().withdraw(stored.get());
      } catch (OpenModelException e) {
        if (stillOpen(s)) {
          throw new AgentException(e.getMessage() + "; the proposal of block " + canonical.text()
              + " is unchanged, call revert_block again");
        }
        // the editor closed: only the stored proposal is left to remove
      }
    }
    boolean removed = remove(s.file(), canonical);
    return new Reverted(canonical, removed, reverted);
  }

  /**
   * AfterGit's automatic passes over a fresh model of the file; each block they resolve that is still an open
   * conflict without a stored proposal becomes a proposal of source "auto" (agents design §2). A resolution longer
   * than a proposal may be is left to the user. {@code remaining}: open conflicts with no proposal at all.
   */
  public AutoResolved autoResolve(String path) throws AgentException {
    ConflictFiles.ConflictFile f = ConflictFiles.resolve(path);
    Snapshot s = snapshot(f);
    Set<BlockId> withProposal = new HashSet<>();
    for (Proposal q : load(s).proposals()) {
      s.view().block(q.block()).ifPresent(b -> withProposal.add(b.id()));
    }
    OpenModels om = openModels.get();
    // the resolver's own resolvers and switches, bound to the file so its format's resolver runs too
    ResolverPipeline pipeline = (om == null ? ResolverPipeline.defaults(false) : om.pipeline(f.file()))
        .forFile(f.file().getName());
    MergeModel fresh = OwnModels.build(f, om, pipeline).model();
    List<BlockId> proposed = new ArrayList<>();
    boolean editor = s.open().isPresent();
    for (MergeBlock mb : fresh.blocks()) {
      if (!mb.isConflict() || mb.state() != BlockState.AUTO_RESOLVED) {
        continue;
      }
      Optional<ModelView.Block> now = s.view().block(BlockId.of(fresh, mb.index()));
      if (now.isEmpty() || now.get().state() != BlockState.UNRESOLVED || withProposal.contains(now.get().id())) {
        continue; // the user, an earlier pass or an agent decided this block
      }
      String text = fresh.resultText(mb.index());
      if (Proposal.chunk(text).length() > MAX_PROPOSAL_CHARS) {
        continue;
      }
      Proposal p = new Proposal(now.get().id(), text, "AfterGit's " + mb.resolvedBy() + " resolver", Proposal.AUTO,
          clock.instant());
      put(s, p);
      if (editor) {
        Offer offer;
        try {
          offer = offer(s, p);
        } catch (AgentException e) {
          throw new AgentException(e.getMessage() + " (stored so far: " + ids(proposed, p.block()) + ")");
        }
        if (offer == Offer.NOT_FOUND) {
          remove(s.file(), p.block());
          continue;
        }
        editor = offer != null;
        if (editor) {
          shown(s, p);
        }
      }
      proposed.add(p.block());
      withProposal.add(p.block());
    }
    int remaining = 0;
    for (ModelView.Block b : s.view().blocks()) {
      if (b.state() == BlockState.UNRESOLVED && !withProposal.contains(b.id())) {
        remaining++;
      }
    }
    return new AutoResolved(proposed, remaining);
  }

  private static String ids(List<BlockId> done, BlockId last) {
    List<String> out = new ArrayList<>();
    for (BlockId id : done) {
      out.add(id.text());
    }
    out.add(last.text());
    return String.join(", ", out);
  }

  /**
   * Offers a stored proposal to the open editor; null when the editor closed meanwhile (the proposal is applied when
   * the file opens again). Any other refusal is an error that says the proposal stays stored.
   */
  private Offer offer(Snapshot s, Proposal p) throws AgentException {
    try {
      return s.open().get().offer(p);
    } catch (OpenModelException e) {
      if (!stillOpen(s)) {
        return null;
      }
      throw new AgentException(e.getMessage() + "; the proposal of block " + p.block().text() + " is stored and "
          + "is offered again when you call again or the file is opened");
    }
  }

  private boolean stillOpen(Snapshot s) {
    OpenModels om = openModels.get();
    return om != null && om.find(s.file().file()).isPresent();
  }

  private static ModelView.Block block(Snapshot s, String text) throws AgentException {
    BlockId id = parse(text);
    return s.view().block(id).orElseThrow(() -> new AgentException("Block " + text + " is not a conflict of "
        + s.file().absolutePath() + " (any more); call get_conflict for the current blocks"));
  }

  private static BlockId parse(String text) throws AgentException {
    try {
      return BlockId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new AgentException(e.getMessage());
    }
  }

  /**
   * Records {@code p} with the fingerprint taken before the snapshot was read, so a change since then is reported
   * stale on the next read; true when earlier proposals of the file were stale and dropped.
   */
  private boolean put(Snapshot s, Proposal p) throws AgentException {
    try {
      return store.put(s.file().repoFile(), s.fingerprint(), p, s.open().isPresent());
    } catch (IOException | IllegalArgumentException e) {
      throw new AgentException("The proposal could not be stored: " + e.getMessage());
    }
  }

  /**
   * The open resolver was offered {@code p}: offered again later (reopened, agents back) it only waits. Best effort:
   * when this fails, the proposal may be applied again at the next open, as before.
   */
  private void shown(Snapshot s, Proposal p) {
    try {
      store.markShown(s.file().repoFile(), List.of(p.block()));
    } catch (IOException e) {
      // the proposal itself is stored and was offered; only the note is missing
    }
  }

  private boolean remove(ConflictFiles.ConflictFile f, BlockId block) throws AgentException {
    try {
      return store.remove(f.repoFile(), block);
    } catch (IOException e) {
      throw new AgentException("The proposal could not be removed: " + e.getMessage());
    }
  }

  Snapshot snapshot(ConflictFiles.ConflictFile f) throws AgentException {
    if (f.kind() != ConflictKind.TEXT) {
      throw new AgentException(f.absolutePath() + " is a " + f.kind().name().toLowerCase(java.util.Locale.ROOT)
          .replace('_', '-') + " conflict; AfterGit resolves it by keeping a side, not with proposals");
    }
    // sizes and links are checked before anything is read, also for an open file
    Fingerprint fp = fingerprint(f);
    OwnModels.Read r = OwnModels.read(f);
    Format format = new Format(r.decodedAs().name(), !r.ownCharset(), r.lineEnds());
    OpenModels om = openModels.get();
    Optional<OpenModel> open = om == null ? Optional.empty() : om.find(f.file());
    if (open.isPresent()) {
      ModelView v = open(() -> open.get().read(ModelView::of));
      for (String t : new String[] {v.ours(), v.theirs(), v.result()}) {
        if (utf8Length(t) > MAX_FILE_BYTES) {
          throw OwnModels.tooLarge(f);
        }
      }
      return new Snapshot(f, open, v, OwnModels.java(f, r, om), format, fp);
    }
    OwnModels.Built b = OwnModels.build(f, r, om, ResolverPipeline.none());
    return new Snapshot(f, open, ModelView.of(b.model()), b.java(), format, fp);
  }

  /** The fingerprint of a checked file (no link, not too large), from one index read. */
  private static Fingerprint fingerprint(ConflictFiles.ConflictFile f) throws AgentException {
    try {
      org.eclipse.jgit.dircache.DirCache cache = f.repoFile().repository().readDirCache();
      OwnModels.check(f, cache);
      return Fingerprint.of(f.repoFile(), cache);
    } catch (IOException e) {
      throw new AgentException("The versions of " + f.absolutePath() + " could not be read: " + e.getMessage());
    }
  }

  /** The stored proposals, compared with the fingerprint of the snapshot. */
  ProposalStore.Loaded load(Snapshot s) throws AgentException {
    try {
      return store.load(s.file().repoFile(), s.fingerprint(), s.open().isPresent());
    } catch (IOException e) {
      throw new AgentException("The stored proposals of " + s.file().absolutePath() + " could not be read: "
          + e.getMessage());
    }
  }

  ProposalStore.Loaded load(ConflictFiles.ConflictFile f, boolean open) throws AgentException {
    return load(f, open, new ConflictFiles.Indexes());
  }

  private ProposalStore.Loaded load(ConflictFiles.ConflictFile f, boolean open, ConflictFiles.Indexes indexes)
      throws AgentException {
    try {
      Fingerprint now = Fingerprint.of(f.repoFile(), indexes.of(f.repoFile().repository()));
      return store.load(f.repoFile(), now, open);
    } catch (IOException e) {
      throw new AgentException("The stored proposals of " + f.absolutePath() + " could not be read: "
          + e.getMessage());
    }
  }

  /** {@code only}: the block to report, or null for every conflict block. */
  static Validation validation(Snapshot s, Map<BlockId, String> replacements, BlockId only) {
    Composition.Composed c = Composition.apply(s.view(), replacements);
    Errors e = errors(s.java(), s.view(), c.text());
    if (!e.known()) {
      return new Validation(false, null, Map.of());
    }
    Map<BlockId, List<LineProblem>> per = new LinkedHashMap<>();
    for (ModelView.Block b : s.view().blocks()) {
      if (only == null || b.id().equals(only)) {
        per.put(b.id(), BlockErrors.newErrors(b, c.ranges().get(b.id()), e.result(), e.ours(), e.theirs()));
      }
    }
    return new Validation(true, e.result(), per);
  }

  private static Errors errors(JavaChecks java, ModelView v, String result) {
    if (java == null) {
      return Errors.UNKNOWN;
    }
    IProgressMonitor monitor = new NullProgressMonitor();
    List<LineProblem> ours = java.errors(v.ours(), monitor);
    List<LineProblem> theirs = ours == null ? null : java.errors(v.theirs(), monitor);
    List<LineProblem> res = theirs == null ? null : java.errors(result, monitor);
    return res == null ? Errors.UNKNOWN : new Errors(ours, theirs, res);
  }

  /** The conflicts git's markers show in the working-tree file (checked: no link, not too large); null if missing. */
  private static Integer markerHunks(ConflictFiles.ConflictFile f) {
    File disk = f.repoFile().file();
    if (!Files.isRegularFile(disk.toPath(), LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    try {
      // marker lines are ASCII: ISO-8859-1 reads any file without failing
      String text = Files.readString(disk.toPath(), StandardCharsets.ISO_8859_1);
      return ConflictMarkerParser.parse(LineText.of(text).lines()).hunks().size();
    } catch (IOException e) {
      return null;
    }
  }

  /** The bytes {@code text} takes in UTF-8 (the size of what an agent receives), counted without encoding it. */
  public static long utf8Length(String text) {
    long n = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        n++;
      } else if (c < 0x800) {
        n += 2;
      } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        n += 4;
        i++;
      } else {
        n += 3;
      }
    }
    return n;
  }

  <T> T open(OpenCall<T> call) throws AgentException {
    try {
      return call.run();
    } catch (OpenModelException e) {
      throw new AgentException(e.getMessage());
    }
  }
}
