package mybatispg;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.postgresql.util.PGobject;

/**
 * 画面から届いたパラメータを、本番のアプリに近い Java の値に直す。
 * <ul>
 * <li>型付きの値（{"$type": "timestamp", "value": "..."} など）を Java の日時などにする</li>
 * <li>int の範囲に収まる整数は Integer にする（Long のままだと int8 でバインドされ、{@code date + ?} や
 * {@code substr(s, ?)} などが「演算子・関数がない」エラーになる。int8 の列と比べてもインデックスは使える）</li>
 * <li>PostgreSQL の型を指定した値（{"$type": "pg", "pgType": "jsonb", "value": "..."}）は、
 * TypeHandler と同じく PGobject にして、その型でバインドする</li>
 * </ul>
 *
 * <p>JSON には日時の型がないため、画面では日時・日付をこの形で送る。本番のアプリでは Java の日時型で渡り、
 * JDBC は timestamp・date としてバインドするので、それに合わせる（文字列のままだと varchar になり、
 * timestamp の列と比べるとエラーになる）。
 */
public final class TypedValues {

  public static final String TYPE_KEY = "$type";
  public static final String VALUE_KEY = "value";
  public static final String PG_TYPE_KEY = "pgType";

  /** 型の名前（schema.name、配列は name[]）。SQL に埋め込むので、これ以外は受け付けない */
  private static final Pattern PG_TYPE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?(\\[\\])*");

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
      if (map.size() == 3 && "pg".equals(map.get(TYPE_KEY)) && map.get(PG_TYPE_KEY) instanceof String
          && map.containsKey(VALUE_KEY)) {
        return pgObject((String) map.get(PG_TYPE_KEY), map.get(VALUE_KEY));
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
    if (value instanceof Long && (Long) value >= Integer.MIN_VALUE && (Long) value <= Integer.MAX_VALUE) {
      return ((Long) value).intValue();
    }
    return value;
  }

  /** PostgreSQL の型を指定した値。TypeHandler が PGobject でするのと同じ */
  static PGobject pgObject(String type, Object value) {
    String t = type.trim();
    if (!PG_TYPE.matcher(t).matches()) {
      throw new IllegalArgumentException("PostgreSQL の型の名前が正しくありません: " + type + "（例: jsonb、uuid、public.status）");
    }
    PGobject o = new PGobject();
    o.setType(t);
    try {
      o.setValue(value == null ? null : value.toString());
    } catch (java.sql.SQLException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    return o;
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
