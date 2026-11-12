package ua.kpi.pocw.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tokenizer shared by indexing, querying and query expansion.
 *
 * <p>A token is a maximal run of ASCII letters and digits, lower-cased, of length {@value
 * #MIN_LEN}..{@value #MAX_LEN}. Longer runs (hashes, glued strings) are dropped. Every other byte,
 * including any non-ASCII byte, is a separator. English stop words are removed. The class is
 * stateless and thread-safe.
 */
public final class Tokenizer {
  public static final int MIN_LEN = 2;
  public static final int MAX_LEN = 32;

  /** Callback receiving each token. */
  @FunctionalInterface
  public interface Sink {
    void accept(String token);
  }

  private static final Set<String> STOP_WORDS =
      Set.of(
          "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any",
          "are", "as", "at", "be", "because", "been", "before", "being", "below", "between",
          "both", "but", "by", "can", "could", "did", "do", "does", "doing", "down", "during",
          "each", "few", "for", "from", "further", "had", "has", "have", "having", "he", "her",
          "here", "hers", "herself", "him", "himself", "his", "how", "if", "in", "into", "is",
          "it", "its", "itself", "me", "more", "most", "my", "myself", "no", "nor", "not", "now",
          "of", "off", "on", "once", "only", "or", "other", "our", "ours", "ourselves", "out",
          "over", "own", "same", "she", "should", "so", "some", "such", "than", "that", "the",
          "their", "theirs", "them", "themselves", "then", "there", "these", "they", "this",
          "those", "through", "to", "too", "under", "until", "up", "upon", "very", "was", "we",
          "were", "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with",
          "would", "you", "your", "yours", "yourself", "yourselves", "shall", "may", "must",
          "said", "one", "also", "us");

  private static final boolean[] WORD_BYTE = new boolean[256];

  static {
    for (int c = '0'; c <= '9'; c++) {
      WORD_BYTE[c] = true;
    }
    for (int c = 'a'; c <= 'z'; c++) {
      WORD_BYTE[c] = true;
      WORD_BYTE[c - 32] = true;
    }
  }

  private Tokenizer() {}

  public static boolean isWordByte(int b) {
    return WORD_BYTE[b & 0xFF];
  }

  public static boolean isStopWord(String token) {
    return STOP_WORDS.contains(token);
  }

  /** Tokenizes {@code data[0..length)} and feeds every kept token into {@code sink}. */
  public static void tokenize(byte[] data, int length, Sink sink) {
    byte[] buf = new byte[MAX_LEN];
    int i = 0;
    while (i < length) {
      while (i < length && !WORD_BYTE[data[i] & 0xFF]) {
        i++;
      }
      int start = i;
      while (i < length && WORD_BYTE[data[i] & 0xFF]) {
        i++;
      }
      int len = i - start;
      if (len < MIN_LEN || len > MAX_LEN) {
        continue;
      }
      for (int k = 0; k < len; k++) {
        byte b = data[start + k];
        buf[k] = (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
      }
      String token = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
      if (!STOP_WORDS.contains(token)) {
        sink.accept(token);
      }
    }
  }

  /** Tokenizes a query string; returns distinct tokens in their first-occurrence order. */
  public static List<String> tokenizeQuery(String text) {
    Set<String> out = new LinkedHashSet<>();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    tokenize(bytes, bytes.length, out::add);
    return new ArrayList<>(out);
  }
}
