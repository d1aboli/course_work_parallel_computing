package ua.kpi.pocw.core;

import static ua.kpi.pocw.testing.Assert.equal;

import java.util.List;
import ua.kpi.pocw.testing.Test;

class TokenizerTest {
  @Test
  void lowercasesSplitsAndDropsStopWords() {
    equal(List.of("quick", "brown", "fox", "2024", "x2"),
        Tokenizer.tokenizeQuery("The QUICK brown-fox, in 2024; x2 a"), "tokens");
  }

  @Test
  void dropsTooShortTooLongAndNonAscii() {
    String longRun = "a".repeat(Tokenizer.MAX_LEN + 1);
    equal(List.of("caf", "ok"), Tokenizer.tokenizeQuery("café " + longRun + " ok x пошук"),
        "non-ASCII bytes are separators");
    equal(List.of(), Tokenizer.tokenizeQuery("пошук системи"), "Cyrillic gives no terms");
  }

  @Test
  void deduplicatesQueryTermsInOrder() {
    equal(List.of("ship", "sea"), Tokenizer.tokenizeQuery("ship SEA ship sea Ship"), "dedupe");
  }
}
