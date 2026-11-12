package ua.kpi.pocw.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal test runner: invokes every {@link Test} method of the listed classes on a fresh
 * instance and reports results. Exit code 1 if any test fails, so a failing test breaks the
 * Docker build.
 */
public final class TestRunner {
  private static final String[] CLASSES = {
    "ua.kpi.pocw.util.JsonTest",
    "ua.kpi.pocw.core.ThreadPoolTest",
    "ua.kpi.pocw.core.TreiberStackTest",
    "ua.kpi.pocw.core.TokenizerTest",
    "ua.kpi.pocw.core.IndexBuilderTest",
    "ua.kpi.pocw.search.SearchTest",
    "ua.kpi.pocw.llm.QueryExpanderTest",
    "ua.kpi.pocw.net.WebSocketTest",
    "ua.kpi.pocw.IntegrationTest",
  };

  private TestRunner() {}

  public static void main(String[] args) throws Exception {
    List<String> only = Arrays.asList(args);
    int passed = 0;
    List<String> failures = new ArrayList<>();
    long t0 = System.nanoTime();
    for (String name : CLASSES) {
      if (!only.isEmpty() && only.stream().noneMatch(name::endsWith)) {
        continue;
      }
      Class<?> cls = Class.forName(name);
      Method[] methods = cls.getDeclaredMethods();
      Arrays.sort(methods, Comparator.comparing(Method::getName));
      for (Method m : methods) {
        if (!m.isAnnotationPresent(Test.class) || Modifier.isStatic(m.getModifiers())) {
          continue;
        }
        String id = cls.getSimpleName() + "." + m.getName();
        long s = System.nanoTime();
        try {
          var ctor = cls.getDeclaredConstructor();
          ctor.setAccessible(true);
          Object instance = ctor.newInstance();
          m.setAccessible(true);
          try {
            m.invoke(instance);
          } finally {
            if (instance instanceof AutoCloseable c) {
              c.close();
            }
          }
          passed++;
          System.out.printf("  PASS %-60s %6d ms%n", id, (System.nanoTime() - s) / 1_000_000);
        } catch (InvocationTargetException e) {
          failures.add(id);
          System.out.printf("  FAIL %s: %s%n", id, e.getCause());
          e.getCause().printStackTrace(System.out);
        }
      }
    }
    System.out.printf("%n%d passed, %d failed in %d ms%n", passed, failures.size(),
        (System.nanoTime() - t0) / 1_000_000);
    if (!failures.isEmpty()) {
      System.out.println("failed: " + failures);
      System.exit(1);
    }
  }
}
