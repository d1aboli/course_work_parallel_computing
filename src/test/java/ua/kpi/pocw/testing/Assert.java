package ua.kpi.pocw.testing;

import java.util.Objects;

/** Assertion helpers (a tiny subset of JUnit's). */
public final class Assert {
  private Assert() {}

  /** Executable that may throw. */
  @FunctionalInterface
  public interface ThrowingRunnable {
    void run() throws Exception;
  }

  public static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  public static void equal(Object expected, Object actual, String message) {
    if (!Objects.equals(expected, actual)) {
      throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
    }
  }

  public static <T extends Throwable> T throwsType(Class<T> type, ThrowingRunnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
      throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
    }
    throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
  }
}
