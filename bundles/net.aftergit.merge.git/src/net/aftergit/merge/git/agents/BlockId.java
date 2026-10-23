package net.aftergit.merge.git.agents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;

/**
 * A conflict block that survives a rebuild of its file's model: the block index plus a hash of its ours, base and
 * theirs text (agents design §2). Written as {@code "<index>:<hash>"}, for example {@code "2:5d41402abc4b"}.
 */
public record BlockId(int index, String hash) {
  private static final int HASH_LENGTH = 12;
  private static final String SHAPE = "A block id looks like \"2:5d41402abc4b\" (index:hash from get_conflict), not ";

  public BlockId {
    if (index < 0) {
      throw new IllegalArgumentException(SHAPE + index + ":" + hash);
    }
    if (hash == null || !hash.matches("[0-9a-f]{" + HASH_LENGTH + "}")) {
      throw new IllegalArgumentException(SHAPE + index + ":" + hash);
    }
  }

  /** The id of block {@code block} of {@code model}. */
  public static BlockId of(MergeModel model, int block) {
    MergeBlock b = model.blocks().get(block);
    return new BlockId(block, hash(model.sideText(block, ThreeSide.LEFT), b.baseText(),
        model.sideText(block, ThreeSide.RIGHT)));
  }

  static String hash(String ours, String base, String theirs) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      for (String s : new String[] {ours, base, theirs}) {
        d.update(s.getBytes(StandardCharsets.UTF_8));
        d.update((byte) 0);
      }
      return HexFormat.of().formatHex(d.digest()).substring(0, HASH_LENGTH);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  /**
   * The conflict block of {@code model} with this hash: this index when its hash matches, else the first conflict
   * that does; -1 when the file has no such conflict (any more).
   */
  public int find(MergeModel model) {
    if (index < model.blocks().size() && matches(model, index)) {
      return index;
    }
    for (int i = 0; i < model.blocks().size(); i++) {
      if (matches(model, i)) {
        return i;
      }
    }
    return -1;
  }

  private boolean matches(MergeModel model, int i) {
    return model.blocks().get(i).isConflict() && of(model, i).hash.equals(hash);
  }

  public String text() {
    return index + ":" + hash;
  }

  /** The id written as {@link #text()}; {@link IllegalArgumentException} for anything else. */
  public static BlockId parse(String text) {
    int colon = text == null ? -1 : text.indexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException(SHAPE + text);
    }
    try {
      return new BlockId(Integer.parseInt(text.substring(0, colon)), text.substring(colon + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(SHAPE + text, e);
    }
  }

  @Override
  public String toString() {
    return text();
  }
}
