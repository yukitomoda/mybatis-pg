package mybatispg;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画面から届いたパラメータのうち、型付きの値（{"$type": "timestamp", "value": "..."} など）を Java の値に直す。
 *
 * <p>JSON には日時の型がないため、画面では日時・日付をこの形で送る。本番のアプリでは Java の日時型で渡り、
 * JDBC は timestamp・date としてバインドするので、それに合わせる（文字列のままだと varchar になり、
 * timestamp の列と比べるとエラーになる）。
 */
public final class TypedValues {

  public static final String TYPE_KEY = "$type";
  public static final String VALUE_KEY = "value";

  private static final Pattern DATE_TIME =
      Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?");

  private TypedValues() {
  }

  /** 中をたどって、型付きの値を直した新しい値を返す */
  @SuppressWarnings("unchecked")
  public static Object decode(Object value) {
    if (value instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) value;
      if (map.size() == 2 && map.get(TYPE_KEY) instanceof String && map.containsKey(VALUE_KEY)) {
        return typed((String) map.get(TYPE_KEY), map.get(VALUE_KEY));
      }
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : map.entrySet()) {
        out.put(e.getKey(), decode(e.getValue()));
      }
      return out;
    }
    if (value instanceof List) {
      List<Object> out = new ArrayList<>();
      for (Object item : (List<Object>) value) {
        out.add(decode(item));
      }
      return out;
    }
    return value;
  }

  private static Object typed(String type, Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    if ("number".equals(type)) {
      return typed(type, text, null);
    }
    Matcher m = DATE_TIME.matcher(text);
    if (!m.matches()) {
      throw new IllegalArgumentException("日時として解釈できません: " + text + "（例: 2026-01-03 23:35:00）");
    }
    return typed(type, text, m);
  }

  private static Object typed(String type, String text, Matcher m) {
    switch (type) {
      case "date":
        if (m.group(4) != null) {
          throw new IllegalArgumentException("日付に時刻は書けません: " + text);
        }
        return Date.valueOf(m.group(1) + "-" + m.group(2) + "-" + m.group(3));
      case "number":
        // 数値の欄に書いた式（{{unixtime}} など）は、画面で計算してから送るので、ここに来るのは計算前の値だけ
        try {
          return new java.math.BigDecimal(text);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("数値として解釈できません: " + text);
        }
      case "timestamp":
        String nanos = m.group(7) == null ? "" : "." + m.group(7);
        return Timestamp.valueOf(m.group(1) + "-" + m.group(2) + "-" + m.group(3) + " "
            + (m.group(4) == null ? "00" : m.group(4)) + ":" + (m.group(5) == null ? "00" : m.group(5)) + ":"
            + (m.group(6) == null ? "00" : m.group(6)) + nanos);
      default:
        throw new IllegalArgumentException("知らない型です: " + type);
    }
  }
}
