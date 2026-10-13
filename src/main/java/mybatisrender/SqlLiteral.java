package mybatisrender;

/**
 * 値を SQL リテラルに変換する。
 * 対象 DB が未決のため、現状は方言に依存しない最小限の変換のみ。
 */
public final class SqlLiteral {

  private SqlLiteral() {
  }

  public static String of(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Boolean) {
      // TODO: 対象 DB に合わせる（例: Oracle は 1/0）
      return ((Boolean) value) ? "TRUE" : "FALSE";
    }
    return "'" + value.toString().replace("'", "''") + "'";
  }
}
