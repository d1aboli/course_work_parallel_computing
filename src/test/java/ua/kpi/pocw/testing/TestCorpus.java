package ua.kpi.pocw.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

/** Generates a deterministic synthetic corpus in a temporary directory. */
public final class TestCorpus implements AutoCloseable {
  private static final String[] WORDS = {
    "river", "mountain", "forest", "ocean", "storm", "castle", "dragon", "knight", "garden",
    "winter", "summer", "letter", "journey", "parallel", "thread", "engine", "village", "silver",
    "golden", "shadow", "whisper", "harbor", "lantern", "meadow", "thunder", "valley", "wizard",
    "archer", "compass", "voyage", "island", "desert", "glacier", "falcon", "orchard", "beacon"
  };

  private final Path dir;

  public TestCorpus(int docs, int wordsPerDoc, long seed) {
    try {
      dir = Files.createTempDirectory("pocw-corpus");
      Random rnd = new Random(seed);
      for (int d = 0; d < docs; d++) {
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < wordsPerDoc; w++) {
          // Zipf-like skew: low indices are much more frequent.
          int idx = (int) Math.min(WORDS.length - 1, Math.abs(rnd.nextGaussian()) * 9);
          sb.append(WORDS[idx]).append(w % 12 == 11 ? ".\n" : " ");
        }
        Path sub = dir.resolve("part" + (d % 3));
        Files.createDirectories(sub);
        Files.writeString(sub.resolve(String.format("doc%04d.txt", d)), sb, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Path dir() {
    return dir;
  }

  public void write(String relative, String text) throws IOException {
    Path p = dir.resolve(relative);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text, StandardCharsets.UTF_8);
  }

  @Override
  public void close() throws IOException {
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
