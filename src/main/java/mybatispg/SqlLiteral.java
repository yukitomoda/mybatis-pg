package mybatispg;

import java.math.BigDecimal;

/**
 * 値を SQL リテラルに変換する。
 * 書式は PostgreSQL に合わせる。
 */
public final class SqlLiteral {

  private SqlLiteral() {
  }

  public static String of(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof BigDecimal) {
      // 1E+3 のような指数表記にしない
      return ((BigDecimal) value).toPlainString();
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Boolean) {
      return ((Boolean) value) ? "TRUE" : "FALSE";
    }
    return "'" + value.toString().replace("'", "''") + "'";
  }
}
