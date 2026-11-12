package ua.kpi.pocw.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import ua.kpi.pocw.util.Json;

/** JVM heap and process resident set size, for the memory part of the study. */
final class Memory {
  private Memory() {}

  static Map<String, Object> snapshot() {
    Runtime rt = Runtime.getRuntime();
    long used = rt.totalMemory() - rt.freeMemory();
    return Json.obj(
        "heap_used_mb", used >> 20,
        "heap_committed_mb", rt.totalMemory() >> 20,
        "heap_max_mb", rt.maxMemory() >> 20,
        "rss_mb", rssMb());
  }

  /** Reads VmRSS from /proc (Linux containers); -1 elsewhere. */
  private static long rssMb() {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
        if (line.startsWith("VmRSS:")) {
          return Long.parseLong(line.replaceAll("\\D+", "")) >> 10;
        }
      }
    } catch (IOException | NumberFormatException e) {
      // not available
    }
    return -1;
  }
}
