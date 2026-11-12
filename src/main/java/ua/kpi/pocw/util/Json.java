package ua.kpi.pocw.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and serializer (RFC 8259).
 *
 * <p>Objects are parsed into {@link LinkedHashMap}, arrays into {@link ArrayList}, numbers into
 * {@link Long} (integers) or {@link Double}, plus {@link String}, {@link Boolean} and {@code null}.
 * The class is stateless and therefore thread-safe.
 */
public final class Json {
  private static final int MAX_DEPTH = 64;

  private Json() {}

  /** Thrown when the input is not valid JSON. */
  public static final class ParseException extends RuntimeException {
    public ParseException(String message, int position) {
      super(message + " at position " + position);
    }
  }

  public static Object parse(String text) {
    Parser p = new Parser(text);
    p.skipWs();
    Object value = p.readValue(0);
    p.skipWs();
    if (p.pos != text.length()) {
      throw new ParseException("Trailing characters", p.pos);
    }
    return value;
  }

  /** Parses text that must be a JSON object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    Object value = parse(text);
    if (!(value instanceof Map)) {
      throw new ParseException("Expected JSON object", 0);
    }
    return (Map<String, Object>) value;
  }

  public static String stringify(Object value) {
    StringBuilder sb = new StringBuilder(128);
    write(sb, value);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      quote(sb, s);
    } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
      sb.append(value);
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        sb.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        sb.append((long) d).append(".0");
      } else {
        sb.append(d);
      }
    } else if (value instanceof Number n) {
      sb.append(n);
    } else if (value instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        quote(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof Collection<?> list) {
      sb.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(sb, item);
      }
      sb.append(']');
    } else if (value instanceof Object[] array) {
      write(sb, List.of(array));
    } else {
      quote(sb, value.toString());
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  /** Builder helper: {@code Json.obj("a", 1, "b", "x")}. Keeps insertion order. */
  public static Map<String, Object> obj(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("Odd number of arguments");
    }
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    void skipWs() {
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }

    Object readValue(int depth) {
      if (depth > MAX_DEPTH) {
        throw new ParseException("Nesting too deep", pos);
      }
      if (pos >= s.length()) {
        throw new ParseException("Unexpected end of input", pos);
      }
      char c = s.charAt(pos);
      return switch (c) {
        case '{' -> readObject(depth);
        case '[' -> readArray(depth);
        case '"' -> readString();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield readNumber();
          }
          throw new ParseException("Unexpected character '" + c + "'", pos);
        }
      };
    }

    private Object literal(String word, Object value) {
      if (!s.startsWith(word, pos)) {
        throw new ParseException("Invalid literal", pos);
      }
      pos += word.length();
      return value;
    }

    private Map<String, Object> readObject(int depth) {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWs();
      if (peek() == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWs();
        if (peek() != '"') {
          throw new ParseException("Expected string key", pos);
        }
        String key = readString();
        skipWs();
        expect(':');
        skipWs();
        map.put(key, readValue(depth + 1));
        skipWs();
        char c = next();
        if (c == '}') {
          return map;
        }
        if (c != ',') {
          throw new ParseException("Expected ',' or '}'", pos - 1);
        }
      }
    }

    private List<Object> readArray(int depth) {
      List<Object> list = new ArrayList<>();
      pos++;
      skipWs();
      if (peek() == ']') {
        pos++;
        return list;
      }
      while (true) {
        skipWs();
        list.add(readValue(depth + 1));
        skipWs();
        char c = next();
        if (c == ']') {
          return list;
        }
        if (c != ',') {
          throw new ParseException("Expected ',' or ']'", pos - 1);
        }
      }
    }

    private String readString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) {
          throw new ParseException("Unterminated string", pos);
        }
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c < 0x20) {
          throw new ParseException("Control character in string", pos - 1);
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        char e = next();
        switch (e) {
          case '"', '\\', '/' -> sb.append(e);
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > s.length()) {
              throw new ParseException("Bad unicode escape", pos);
            }
            try {
              sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            } catch (NumberFormatException ex) {
              throw new ParseException("Bad unicode escape", pos);
            }
            pos += 4;
          }
          default -> throw new ParseException("Bad escape", pos - 1);
        }
      }
    }

    private Object readNumber() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      boolean isDouble = false;
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c >= '0' && c <= '9') {
          pos++;
        } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          isDouble = true;
          pos++;
        } else {
          break;
        }
      }
      String num = s.substring(start, pos);
      try {
        if (!isDouble) {
          return Long.parseLong(num);
        }
        return Double.parseDouble(num);
      } catch (NumberFormatException ex) {
        throw new ParseException("Bad number '" + num + "'", start);
      }
    }

    private char peek() {
      return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() {
      if (pos >= s.length()) {
        throw new ParseException("Unexpected end of input", pos);
      }
      return s.charAt(pos++);
    }

    private void expect(char c) {
      if (next() != c) {
        throw new ParseException("Expected '" + c + "'", pos - 1);
      }
    }
  }
}
