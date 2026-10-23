package net.aftergit.merge.mcp.service;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;

/**
 * Proposals per conflicted file, in memory and in {@code .git/aftergit/proposals/<sha1 of the path>.json}
 * (agents design §2). Every read compares the stored {@link Fingerprint} with the conflict as it is now; on a
 * mismatch the file's proposals are deleted and the read reports {@code stale}. Methods are short and call nothing
 * outside, so callers may hold no other lock while they wait for one. Every store and {@link #deleteAll} share one
 * lock, so "Delete stored proposals" never runs between the steps of a write and also empties the memory of every
 * live store.
 */
public final class ProposalStore {
  static final String DIR = "aftergit/proposals";
  private static final String TMP_PREFIX = "proposals";
  private static final String TMP_SUFFIX = ".tmp";
  private static final int FORMAT = 1;
  /** A proposal's text is at most this long (agents design §4); longer stored ones are discarded on load. */
  static final int MAX_TEXT = 200_000;
  /** A stored record is read only up to this size; room for several proposals with escaped text. */
  static final long MAX_RECORD_BYTES = 64L * MAX_TEXT;

  /**
   * A file's proposals; {@code stale}: there were some for another conflict, now deleted; {@code shown}: the blocks
   * whose proposal an open resolver was already offered (agents design §3: offered again, they only wait).
   */
  public record Loaded(List<Proposal> proposals, boolean stale, Set<BlockId> shown) {
    public Loaded {
      proposals = List.copyOf(proposals);
      shown = Set.copyOf(shown);
    }

    public Loaded(List<Proposal> proposals, boolean stale) {
      this(proposals, stale, Set.of());
    }
  }

  private record Entry(Fingerprint fingerprint, List<Proposal> proposals, Set<BlockId> shown) {
    Entry {
      shown = Set.copyOf(shown);
    }
  }

  private static final Entry UNREADABLE = new Entry(new Fingerprint("?", "?", "?", "?"), List.of(), Set.of());

  /** A {@code proposals*.tmp} older than this is a leftover of a write that never finished (the process died). */
  static final Duration LEFTOVER_AGE = Duration.ofMinutes(10);

  private static final Object LOCK = new Object();
  /** Counts {@link #deleteAll} calls; a store whose {@link #seen} differs forgets its memory. Guarded by LOCK. */
  private static long generation;

  private final Map<String, Entry> memory = new HashMap<>();
  private final Set<Path> swept = new HashSet<>();
  private long seen;
  /** Set when the server stopped: from then on nothing under .git/aftergit is read, written or deleted. */
  private volatile boolean closed;

  /**
   * The proposals of {@code file} when they still belong to its conflict. With {@code openInEditor} only the stages
   * are compared (the resolver may have saved the file) and the stored fingerprint is brought up to date.
   */
  public Loaded load(RepoFile file, Fingerprint now, boolean openInEditor) throws IOException {
    synchronized (LOCK) {
      if (closed) {
        return new Loaded(List.of(), false);
      }
      catchUp();
      return loadLocked(file, now, openInEditor);
    }
  }

  private Loaded loadLocked(RepoFile file, Fingerprint now, boolean openInEditor) throws IOException {
    String key = key(file);
    Entry e = memory.containsKey(key) ? memory.get(key) : read(file);
    if (e == null) {
      return new Loaded(List.of(), false);
    }
    boolean same = openInEditor ? e.fingerprint().sameStages(now) : e.fingerprint().equals(now);
    if (!same) {
      discard(file);
      return new Loaded(List.of(), true);
    }
    if (!e.fingerprint().equals(now)) {
      e = new Entry(now, e.proposals(), e.shown());
      write(file, json(file, e));
    }
    memory.put(key, e);
    return new Loaded(e.proposals(), false, e.shown());
  }

  /**
   * Records {@code proposal}, replacing one for the same block; true when earlier proposals were stale and dropped.
   * {@link IllegalArgumentException} for a text over {@link #MAX_TEXT} (nothing changes) or a record that would be
   * too large to read back (the proposal is not recorded; only what a {@link #load} does may have happened: stale
   * proposals dropped, an open file's fingerprint brought up to date).
   */
  public boolean put(RepoFile file, Fingerprint now, Proposal proposal, boolean openInEditor) throws IOException {
    if (proposal.text().length() > MAX_TEXT) {
      throw new IllegalArgumentException("A proposal holds at most " + MAX_TEXT + " characters");
    }
    synchronized (LOCK) {
      checkOpen();
      catchUp();
      return putLocked(file, now, proposal, openInEditor);
    }
  }

  private boolean putLocked(RepoFile file, Fingerprint now, Proposal proposal, boolean openInEditor)
      throws IOException {
    Loaded l = loadLocked(file, now, openInEditor);
    List<Proposal> list = new ArrayList<>();
    for (Proposal q : l.proposals()) {
      if (!q.block().equals(proposal.block())) {
        list.add(q);
      }
    }
    list.add(proposal);
    Set<BlockId> shown = new HashSet<>(l.shown());
    shown.remove(proposal.block()); // a new proposal: no resolver saw it yet
    Entry e = new Entry(now, List.copyOf(list), shown);
    byte[] bytes = json(file, e);
    if (bytes.length > MAX_RECORD_BYTES) {
      throw new IllegalArgumentException("The proposals of " + file.path() + " would take more than "
          + MAX_RECORD_BYTES + " bytes");
    }
    write(file, bytes);
    memory.put(key(file), e);
    return l.stale();
  }

  /** Forgets the proposal of {@code block}; the file goes with the last one. False when there was none. */
  public boolean remove(RepoFile file, BlockId block) throws IOException {
    synchronized (LOCK) {
      if (closed) {
        return false; // a discard in a resolver after the switch went off: nothing to do
      }
      catchUp();
      return removeLocked(file, block);
    }
  }

  private boolean removeLocked(RepoFile file, BlockId block) throws IOException {
    String key = key(file);
    Entry e = memory.containsKey(key) ? memory.get(key) : read(file);
    if (e == null) {
      return false;
    }
    List<Proposal> list = new ArrayList<>(e.proposals());
    if (!list.removeIf(q -> q.block().equals(block))) {
      return false;
    }
    if (list.isEmpty()) {
      discard(file);
    } else {
      Set<BlockId> shown = new HashSet<>(e.shown());
      shown.remove(block);
      Entry n = new Entry(e.fingerprint(), List.copyOf(list), shown);
      write(file, json(file, n));
      memory.put(key, n);
    }
    return true;
  }

  /**
   * Notes that an open resolver was offered the proposals of {@code blocks} (agents design §3): when they are offered
   * again (the file opens again, agents come back) they only wait for the user, so a decision the user took in the
   * resolver (undo, revert, an edit) is never undone at once. Does nothing for blocks without a proposal.
   */
  public void markShown(RepoFile file, Collection<BlockId> blocks) throws IOException {
    synchronized (LOCK) {
      if (closed) {
        return;
      }
      catchUp();
      String key = key(file);
      Entry e = memory.containsKey(key) ? memory.get(key) : read(file);
      if (e == null || e == UNREADABLE) {
        return;
      }
      Set<BlockId> shown = new HashSet<>(e.shown());
      for (Proposal p : e.proposals()) {
        if (blocks.contains(p.block())) {
          shown.add(p.block());
        }
      }
      if (shown.equals(e.shown())) {
        memory.put(key, e);
        return;
      }
      Entry n = new Entry(e.fingerprint(), e.proposals(), shown);
      write(file, json(file, n));
      memory.put(key, n);
    }
  }

  /** The user marked {@code file} resolved: its record goes (agents design §1). Nothing once closed. */
  public void resolved(RepoFile file) throws IOException {
    synchronized (LOCK) {
      if (closed) {
        return;
      }
      catchUp();
      discard(file);
      deleteIfEmpty(path(file).getParent());
      deleteIfEmpty(path(file).getParent().getParent());
    }
  }

  /**
   * Deletes the records of {@code repositories} whose path is no longer conflicted in the index (resolved while the
   * server was off, or by another tool); returns how many. A record that cannot be read is left to the next read of
   * its file. Nothing once closed.
   */
  public int sweepResolved(Collection<Repository> repositories) throws IOException {
    synchronized (LOCK) {
      if (closed) {
        return 0;
      }
      catchUp();
      int n = 0;
      for (Repository r : repositories) {
        Path dir = r.getDirectory().toPath().resolve(DIR);
        if (!Files.isDirectory(dir)) {
          continue;
        }
        Set<String> conflicted = new HashSet<>();
        DirCache cache = r.readDirCache();
        for (int i = 0; i < cache.getEntryCount(); i++) {
          DirCacheEntry e = cache.getEntry(i);
          if (e.getStage() != DirCacheEntry.STAGE_0) {
            conflicted.add(e.getPathString());
          }
        }
        try (Stream<Path> files = Files.list(dir)) {
          for (Path f : (Iterable<Path>) files::iterator) {
            String path = f.getFileName().toString().endsWith(".json") ? recordPath(f) : null;
            if (path != null && !conflicted.contains(path)) {
              memory.remove(r.getDirectory().getAbsolutePath() + '\n' + path);
              Files.deleteIfExists(f);
              n++;
            }
          }
        }
        deleteIfEmpty(dir);
        deleteIfEmpty(dir.getParent());
      }
      return n;
    }
  }

  /** The repository path a record belongs to, or null when it cannot be read. */
  private static String recordPath(Path record) {
    try {
      if (!Files.isRegularFile(record) || Files.size(record) > MAX_RECORD_BYTES) {
        return null;
      }
      JsonObject o = JsonParser.parseString(Files.readString(record, StandardCharsets.UTF_8)).getAsJsonObject();
      String path = o.get("path").getAsString();
      // the name is the hash of the path: a record that says otherwise is not ours to judge here
      return record.getFileName().toString().equals(Fingerprint.sha1(path.getBytes(StandardCharsets.UTF_8))
          + ".json") ? path : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** Forgets what is in memory (the switch went off); the files stay. */
  public void dropMemory() {
    synchronized (LOCK) {
      memory.clear();
    }
  }

  /**
   * The server stopped (agents design §1): forgets what is in memory and from now on leaves the files alone, also
   * for a call or an open resolver that still holds this store. Reads find nothing, removes do nothing, puts fail.
   */
  public void close() {
    closed = true;
    dropMemory();
  }

  private void checkOpen() throws IOException {
    if (closed) {
      throw new IOException("The AfterGit MCP server is off");
    }
  }

  /**
   * Deletes every stored proposal file of {@code repositories} ("Delete stored proposals"), and any leftover
   * temporary file; returns how many proposal files. Every live store forgets what it holds in memory.
   */
  public static int deleteAll(Collection<Repository> repositories) throws IOException {
    synchronized (LOCK) {
      generation++;
      return deleteAllLocked(repositories);
    }
  }

  private static int deleteAllLocked(Collection<Repository> repositories) throws IOException {
    int n = 0;
    for (Repository r : repositories) {
      Path dir = r.getDirectory().toPath().resolve(DIR);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> files = Files.list(dir)) {
        for (Path f : (Iterable<Path>) files::iterator) {
          String name = f.getFileName().toString();
          if (name.endsWith(".json")) {
            Files.delete(f);
            n++;
          } else if (leftover(name)) {
            Files.deleteIfExists(f);
          }
        }
      }
      deleteIfEmpty(dir);
      deleteIfEmpty(dir.getParent());
    }
    return n;
  }

  private void catchUp() {
    if (seen != generation) {
      memory.clear();
      seen = generation;
    }
  }

  private static boolean leftover(String name) {
    return name.startsWith(TMP_PREFIX) && name.endsWith(TMP_SUFFIX);
  }

  private static void deleteIfEmpty(Path dir) throws IOException {
    try {
      Files.deleteIfExists(dir);
    } catch (DirectoryNotEmptyException e) {
      // something else lives there: keep it
    }
  }

  public static Path path(RepoFile file) {
    return file.repository().getDirectory().toPath().resolve(DIR)
        .resolve(Fingerprint.sha1(file.path().getBytes(StandardCharsets.UTF_8)) + ".json");
  }

  private static String key(RepoFile file) {
    return file.repository().getDirectory().getAbsolutePath() + '\n' + file.path();
  }

  private void discard(RepoFile file) throws IOException {
    memory.remove(key(file));
    Files.deleteIfExists(path(file));
  }

  private static Entry read(RepoFile file) throws IOException {
    Path p = path(file);
    if (!Files.isRegularFile(p)) {
      return null;
    }
    if (Files.size(p) > MAX_RECORD_BYTES) {
      return UNREADABLE;
    }
    String json;
    try {
      json = Files.readString(p, StandardCharsets.UTF_8);
    } catch (CharacterCodingException e) {
      return UNREADABLE;
    }
    try {
      JsonObject o = JsonParser.parseString(json).getAsJsonObject();
      if (o.get("format").getAsInt() != FORMAT || !file.path().equals(o.get("path").getAsString())) {
        return UNREADABLE;
      }
      JsonObject f = o.getAsJsonObject("fingerprint");
      Fingerprint fp = new Fingerprint(f.get("base").getAsString(), f.get("ours").getAsString(),
          f.get("theirs").getAsString(), f.get("workingTree").getAsString());
      List<Proposal> list = new ArrayList<>();
      Set<BlockId> shown = new HashSet<>();
      for (JsonElement el : o.getAsJsonArray("proposals")) {
        JsonObject q = el.getAsJsonObject();
        String text = q.get("text").getAsString();
        if (text.length() > MAX_TEXT) {
          return UNREADABLE;
        }
        Proposal one = new Proposal(BlockId.parse(q.get("block").getAsString()), text,
            q.get("rationale").getAsString(), q.get("source").getAsString(), Instant.parse(q.get("time").getAsString()));
        list.add(one);
        if (q.has("shown") && q.get("shown").getAsBoolean()) {
          shown.add(one.block());
        }
      }
      return new Entry(fp, List.copyOf(list), shown);
    } catch (RuntimeException e) {
      return UNREADABLE; // malformed: never matches a fingerprint, reported stale and deleted
    }
  }

  private static byte[] json(RepoFile file, Entry e) {
    JsonObject o = new JsonObject();
    o.addProperty("format", FORMAT);
    o.addProperty("path", file.path());
    JsonObject f = new JsonObject();
    f.addProperty("base", e.fingerprint().base());
    f.addProperty("ours", e.fingerprint().ours());
    f.addProperty("theirs", e.fingerprint().theirs());
    f.addProperty("workingTree", e.fingerprint().workingTree());
    o.add("fingerprint", f);
    JsonArray list = new JsonArray();
    for (Proposal p : e.proposals()) {
      JsonObject q = new JsonObject();
      q.addProperty("block", p.block().text());
      q.addProperty("text", p.text());
      q.addProperty("rationale", p.rationale());
      q.addProperty("source", p.source());
      q.addProperty("time", p.time().toString());
      if (e.shown().contains(p.block())) {
        q.addProperty("shown", true);
      }
      list.add(q);
    }
    o.add("proposals", list);
    return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o)
        .getBytes(StandardCharsets.UTF_8);
  }

  private void write(RepoFile file, byte[] json) throws IOException {
    Path target = path(file);
    Path dir = target.getParent();
    Files.createDirectories(dir);
    if (swept.add(dir)) {
      sweepLeftovers(dir);
    }
    Path tmp = Files.createTempFile(dir, TMP_PREFIX, TMP_SUFFIX);
    try {
      Files.write(tmp, json);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** Deletes temporary files that a write of a process that died left behind; young ones may be in progress. */
  private void sweepLeftovers(Path dir) {
    FileTime before = FileTime.from(Instant.now().minus(LEFTOVER_AGE));
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : (Iterable<Path>) files::iterator) {
        if (leftover(f.getFileName().toString()) && Files.getLastModifiedTime(f).compareTo(before) < 0) {
          Files.deleteIfExists(f);
        }
      }
    } catch (IOException e) {
      // a leftover costs only disk space; the write itself goes on
    }
  }
}
