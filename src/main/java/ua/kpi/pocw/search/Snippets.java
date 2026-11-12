package ua.kpi.pocw.search;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ua.kpi.pocw.core.Tokenizer;

/**
 * Builds a short text fragment around the first whole-word occurrence of a matched term.
 *
 * <p>The file is streamed in blocks and scanning stops as soon as a match is found, so a snippet
 * costs one partial read. Matching uses the same word definition as {@link Tokenizer} (ASCII
 * letters and digits, case-insensitive): "parallel" does not match inside "unparalleled", which
 * keeps the snippet consistent with ranking. Window boundaries are moved to word boundaries, runs
 * of whitespace are collapsed and non-ASCII bytes are replaced, so the output is always valid text.
 */
public final class Snippets {
  public static final int WINDOW = 180;
  private static final int BLOCK = 64 * 1024;

  private Snippets() {}

  /** Tries {@code terms} in order and returns the snippet for the first one found. */
  public static String build(Path file, List<String> terms) {
    if (terms.isEmpty()) {
      return "";
    }
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buf = new byte[BLOCK + WINDOW * 2];
      int carry = 0;
      long fileOffset = 0;
      byte[] head = null;
      while (true) {
        int r = in.readNBytes(buf, carry, BLOCK);
        int len = carry + r;
        if (head == null) {
          head = java.util.Arrays.copyOf(buf, Math.min(len, WINDOW));
        }
        for (String term : terms) {
          int pos = findWord(buf, len, term.getBytes(StandardCharsets.ISO_8859_1));
          if (pos >= 0) {
            return extract(buf, len, pos, term.length());
          }
        }
        if (r < BLOCK) {
          return render(head, 0, head.length) + (fileOffset + len > WINDOW ? " …" : "");
        }
        // Keep the tail so that a word crossing the block boundary is still found.
        carry = Math.min(len, WINDOW);
        System.arraycopy(buf, len - carry, buf, 0, carry);
        fileOffset += len - carry;
      }
    } catch (IOException e) {
      return "";
    }
  }

  static int findWord(byte[] buf, int len, byte[] word) {
    outer:
    for (int i = 0; i + word.length <= len; i++) {
      if (i > 0 && Tokenizer.isWordByte(buf[i - 1])) {
        continue;
      }
      for (int k = 0; k < word.length; k++) {
        byte b = buf[i + k];
        if (b >= 'A' && b <= 'Z') {
          b += 32;
        }
        if (b != word[k]) {
          continue outer;
        }
      }
      int end = i + word.length;
      if (end < len && Tokenizer.isWordByte(buf[end])) {
        continue;
      }
      return i;
    }
    return -1;
  }

  private static String extract(byte[] buf, int len, int pos, int wordLen) {
    int start = Math.max(0, pos - WINDOW / 3);
    int end = Math.min(len, start + WINDOW);
    while (start > 0 && start < pos && Tokenizer.isWordByte(buf[start - 1])) {
      start++;
    }
    while (end < len && end > pos + wordLen && Tokenizer.isWordByte(buf[end])) {
      end--;
    }
    String core = render(buf, start, end);
    return (start > 0 ? "… " : "") + core + (end < len ? " …" : "");
  }

  private static String render(byte[] buf, int from, int to) {
    StringBuilder sb = new StringBuilder(to - from);
    boolean space = false;
    for (int i = from; i < to; i++) {
      int b = buf[i] & 0xFF;
      if (b <= ' ' || b == 0x7F) {
        space = sb.length() > 0;
        continue;
      }
      if (space) {
        sb.append(' ');
        space = false;
      }
      sb.append(b < 0x80 ? (char) b : '�');
    }
    String s = sb.toString().replaceAll("�+", "�");
    return s.trim();
  }
}
