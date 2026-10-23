package net.aftergit.merge.git;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Text of a file's bytes without losing any of them. Merge inputs are decoded strictly in the file's charset; when one
 * of them is not valid there, all of them are read as ISO-8859-1, which maps every byte to one character and back,
 * so writing the text in the same charset gives the same bytes.
 */
public final class Decoding {
  /** The charset that decodes any bytes and encodes them back unchanged. */
  public static final Charset FALLBACK = StandardCharsets.ISO_8859_1;

  private Decoding() {}

  /** True when every non-null text decodes in {@code charset} without malformed or unmappable bytes. */
  public static boolean decodesAll(Charset charset, byte[]... texts) {
    for (byte[] t : texts) {
      if (t != null && !decodes(t, charset)) {
        return false;
      }
    }
    return true;
  }

  /**
   * True when {@code bytes} decode strictly in {@code charset} and encoding the text gives the same bytes again
   * (a UTF-16 file with a little-endian BOM decodes, but would be written back big-endian, so it does not qualify).
   */
  public static boolean decodes(byte[] bytes, Charset charset) {
    try {
      CharBuffer text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      return Arrays.equals(encode(text.toString(), charset), bytes);
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  /** {@code bytes} as text in {@code charset} (null for null); precondition: {@link #decodesAll} accepted
   * the charset (otherwise bad bytes become U+FFFD). */
  public static String decode(byte[] bytes, Charset charset) {
    return bytes == null ? null : new String(bytes, charset);
  }

  /** {@code text} in {@code charset}; fails rather than replacing a character the charset cannot hold. */
  public static byte[] encode(String text, Charset charset) throws CharacterCodingException {
    ByteBuffer b = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
    byte[] out = new byte[b.remaining()];
    b.get(out);
    return out;
  }
}
