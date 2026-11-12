package ua.kpi.pocw.util;

import static ua.kpi.pocw.testing.Assert.check;
import static ua.kpi.pocw.testing.Assert.equal;
import static ua.kpi.pocw.testing.Assert.throwsType;

import java.util.List;
import java.util.Map;
import ua.kpi.pocw.testing.Test;

class JsonTest {
  @Test
  void parsesNestedValues() {
    Map<String, Object> m =
        Json.parseObject("{\"a\": 1, \"b\": [true, null, 2.5, \"x\\n\\u0041\"], \"c\": {}}");
    equal(1L, m.get("a"), "integer");
    equal(List.of(true, 2.5, "x\nA"), ((List<?>) m.get("b")).stream()
        .filter(java.util.Objects::nonNull).toList(), "array");
    check(((List<?>) m.get("b")).contains(null), "null kept");
    equal(Map.of(), m.get("c"), "empty object");
  }

  @Test
  void roundTripsEscapes() {
    String s = "quote\" backslash\\ tab\t ctl\u0001 юнікод";
    equal(s, Json.parse(Json.stringify(s)), "round trip");
    equal("{\"k\":[1,2.5,null],\"s\":\"a\"}",
        Json.stringify(Json.obj("k", java.util.Arrays.asList(1, 2.5, null), "s", "a")),
        "serialize");
  }

  @Test
  void rejectsInvalidInput() {
    throwsType(Json.ParseException.class, () -> Json.parse("{\"a\":}"));
    throwsType(Json.ParseException.class, () -> Json.parse("[1,2"));
    throwsType(Json.ParseException.class, () -> Json.parse("{} x"));
    throwsType(Json.ParseException.class, () -> Json.parseObject("[1]"));
    throwsType(Json.ParseException.class, () -> Json.parse("\"abc"));
  }
}
