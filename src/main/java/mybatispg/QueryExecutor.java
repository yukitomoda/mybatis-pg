package mybatispg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * statement を DB で実行する。
 *
 * <p>実行は必ずトランザクションの中で行い、既定ではロールバックする。commit を指定したときだけコミットする。
 * SQL とバインド値は本番と同じく MyBatis で組み立てる（値は TypeHandler で設定する）。
 */
public final class QueryExecutor {

  public interface ConnectionFactory {
    Connection open() throws SQLException;
  }

  public static final class Options {
    /** true ならコミットする。false（既定）ならロールバックする */
    public boolean commit;
    /** 結果セットから読む最大行数 */
    public int maxRows = 1000;
    public int timeoutSeconds = 300;
  }

  public static final class ResultTable {
    public final List<String> columns = new ArrayList<>();
    public final List<String> columnTypes = new ArrayList<>();
    /** 値は文字列で返す（DB の表記のまま見せるため）。NULL は null */
    public final List<List<String>> rows = new ArrayList<>();
    /** maxRows を超える行があった */
    public boolean truncated;
  }

  public static final class Result {
    public final String sql;
    public final List<ResultTable> resultSets = new ArrayList<>();
    public final List<Integer> updateCounts = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public boolean committed;
    public long elapsedMillis;

    Result(String sql) {
      this.sql = sql;
    }
  }

  /** 実行に失敗した。トランザクションはロールバック済み */
  public static final class ExecutionException extends Exception {
    ExecutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private QueryExecutor() {
  }

  public static Result execute(SqlRenderer renderer, String statementId, Object parameters,
      ConnectionFactory connections, Options options) throws ExecutionException {
    // SQL の組み立てで失敗した場合（OGNL の評価エラーなど）は、接続する前にそのまま投げる
    SqlRenderer.Bound bound = renderer.bind(statementId, parameters);
    Result result = new Result(bound.sql());
    result.warnings.addAll(bound.warnings);

    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      long start = System.nanoTime();
      try (PreparedStatement ps = c.prepareStatement(bound.sql())) {
        ps.setQueryTimeout(options.timeoutSeconds);
        // 1 行多く読み、上限を超えたかを判定する
        ps.setMaxRows(options.maxRows + 1);
        bound.setParameters(ps);
        boolean isResultSet = ps.execute();
        while (true) {
          if (isResultSet) {
            try (ResultSet rs = ps.getResultSet()) {
              result.resultSets.add(read(rs, options.maxRows));
            }
          } else {
            int count = ps.getUpdateCount();
            if (count < 0) {
              break;
            }
            result.updateCounts.add(count);
          }
          isResultSet = ps.getMoreResults();
        }
        for (SQLWarning w = ps.getWarnings(); w != null; w = w.getNextWarning()) {
          result.warnings.add(w.getMessage());
        }
      }
      result.elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      if (options.commit) {
        c.commit();
        result.committed = true;
      } else {
        c.rollback();
      }
      return result;
    } catch (SQLException | RuntimeException e) {
      String rollback = rollback(c);
      throw new ExecutionException(Errors.describe(e) + hint(e) + "\n" + rollback, e);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない
      }
    }
  }

  public static final class ExplainOptions {
    /** true なら実際に実行して計測する（EXPLAIN ANALYZE）。トランザクションは必ずロールバックする */
    public boolean analyze = true;
    /** 読んだブロック数も出す（ANALYZE のときだけ） */
    public boolean buffers = true;
    /** 読み取り専用のトランザクションで実行する（更新系はエラーになる） */
    public boolean readOnly;
    public int timeoutSeconds = 300;
    /**
     * 汎用プランの確認。PREPARE してから計画を 5 回作り（実行はしない）、6 回目の計画を EXPLAIN する。
     * JDBC が同じ SQL を繰り返し実行したときに、サーバが選ぶ計画が見える。
     */
    public boolean sixthExecution;
    /** 接続パラメータに stringtype=unspecified があるか（PREPARE するときの文字列の型が変わる） */
    public boolean stringUnspecified;
  }

  public static final class ExplainResult {
    /** EXPLAIN を付ける前の ? 付き SQL */
    public final String sql;
    /** EXPLAIN (FORMAT JSON) の結果をそのまま */
    public final String plan;
    public final List<String> warnings = new ArrayList<>();
    public long elapsedMillis;
    /** 6 回目の計画を見たとき、汎用プラン（パラメータの値を使わない計画）だったか。それ以外は null */
    public Boolean generic;

    ExplainResult(String sql, String plan) {
      this.sql = sql;
      this.plan = plan;
    }
  }

  /**
   * 本番と同じ ? 付き SQL とバインド値で EXPLAIN を実行する。結果は JSON 形式。
   * ANALYZE では SQL を実際に実行するが、トランザクションは必ずロールバックする。
   */
  public static ExplainResult explain(SqlRenderer renderer, String statementId, Object parameters,
      ConnectionFactory connections, ExplainOptions options) throws ExecutionException {
    if (options.sixthExecution) {
      return explainSixth(renderer, statementId, parameters, connections, options);
    }
    SqlRenderer.Bound bound = renderer.bind(statementId, parameters);
    // BUFFERS は ANALYZE と一緒でないと使えない（PostgreSQL 11）
    String explain = "EXPLAIN (ANALYZE " + options.analyze + ", BUFFERS " + (options.analyze && options.buffers)
        + ", FORMAT JSON) ";

    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      if (options.readOnly) {
        // トランザクションの最初の文でなければならない
        try (Statement s = c.createStatement()) {
          s.execute("SET TRANSACTION READ ONLY");
        }
      }
      long start = System.nanoTime();
      String plan;
      try (PreparedStatement ps = c.prepareStatement(explain + bound.sql())) {
        ps.setQueryTimeout(options.timeoutSeconds);
        bound.setParameters(ps);
        try (ResultSet rs = ps.executeQuery()) {
          StringBuilder sb = new StringBuilder();
          while (rs.next()) {
            sb.append(rs.getString(1));
          }
          plan = sb.toString();
        }
      }
      ExplainResult result = new ExplainResult(bound.sql(), plan);
      result.warnings.addAll(bound.warnings);
      result.elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      c.rollback();
      return result;
    } catch (SQLException | RuntimeException e) {
      String rollback = rollback(c);
      throw new ExecutionException(Errors.describe(e) + hint(e) + "\n" + rollback, e);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない
      }
    }
  }

  /** PREPARE する文の名前 */
  private static final String PREPARED = "mybatis_pg_explain";

  /** 汎用プランの確認: PREPARE して、計画を 5 回作ってから 6 回目を EXPLAIN する */
  private static ExplainResult explainSixth(SqlRenderer renderer, String statementId, Object parameters,
      ConnectionFactory connections, ExplainOptions options) throws ExecutionException {
    SqlRenderer.Bound bound = renderer.bind(statementId, parameters);
    SqlRenderer.Result rendered = renderer.render(statementId, parameters);
    if (rendered.bindings.isEmpty()) {
      throw new IllegalStateException("バインドするパラメータがないため、汎用プランと値ごとの計画の違いはありません");
    }
    // パラメータの型は JDBC が送る型に合わせる。値は型を決めないリテラルで渡す
    List<String> types = new ArrayList<>();
    List<String> values = new ArrayList<>();
    for (SqlRenderer.Binding b : rendered.bindings) {
      types.add(pgType(b.value, options.stringUnspecified));
      values.add(SqlLiteral.of(b.value));
    }
    String prepare = "PREPARE " + PREPARED + "(" + String.join(", ", types) + ") AS " + rendered.numberedSql;
    String execute = "EXECUTE " + PREPARED + "(" + String.join(", ", values) + ")";
    String explain = "EXPLAIN (ANALYZE " + options.analyze + ", BUFFERS " + (options.analyze && options.buffers)
        + ", FORMAT JSON) " + execute;

    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      long start = System.nanoTime();
      String plan;
      try (Statement s = c.createStatement()) {
        if (options.readOnly) {
          s.execute("SET TRANSACTION READ ONLY");
        }
        s.setQueryTimeout(options.timeoutSeconds);
        s.execute(prepare);
        // 計画だけを作る（実行しない）。サーバは 5 回までは値ごとの計画を作り、6 回目から汎用プランを使うかを決める
        for (int i = 0; i < 5; i++) {
          try (ResultSet rs = s.executeQuery("EXPLAIN (FORMAT JSON) " + execute)) {
            while (rs.next()) {
              // 読み捨てる
            }
          }
        }
        try (ResultSet rs = s.executeQuery(explain)) {
          StringBuilder sb = new StringBuilder();
          while (rs.next()) {
            sb.append(rs.getString(1));
          }
          plan = sb.toString();
        }
      }
      ExplainResult result = new ExplainResult(bound.sql(), plan);
      result.warnings.addAll(bound.warnings);
      result.elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      // 汎用プランでは、条件にパラメータ（$1 など）がそのまま残る
      result.generic = Pattern.compile("\\$\\d+").matcher(plan).find();
      c.rollback();
      return result;
    } catch (SQLException | RuntimeException e) {
      String rollback = rollback(c);
      throw new ExecutionException(Errors.describe(e) + hint(e) + "\n" + rollback, e);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない（PREPARE した文も接続とともに消える）
      }
    }
  }

  /**
   * JDBC（pgjdbc）がパラメータに付ける型。MyBatis の TypeHandler が値の Java の型で setXxx を呼ぶのに合わせる。
   * unknown は、サーバが文脈から型を決める（stringtype=unspecified の文字列や NULL と同じ）。
   */
  static String pgType(Object value, boolean stringUnspecified) {
    if (value == null) {
      return "unknown";
    }
    if (value instanceof String) {
      return stringUnspecified ? "unknown" : "varchar";
    }
    if (value instanceof Long) {
      return "int8";
    }
    if (value instanceof Integer) {
      return "int4";
    }
    if (value instanceof Short) {
      return "int2";
    }
    if (value instanceof java.math.BigDecimal) {
      return "numeric";
    }
    if (value instanceof Double) {
      return "float8";
    }
    if (value instanceof Float) {
      return "float4";
    }
    if (value instanceof Boolean) {
      return "bool";
    }
    if (value instanceof java.sql.Timestamp) {
      return "timestamp";
    }
    if (value instanceof java.sql.Date) {
      return "date";
    }
    return "unknown";
  }

  /** 原因がわかりにくいエラーに説明を足す */
  private static String hint(Exception e) {
    String m = String.valueOf(e.getMessage());
    if (m.contains("No value specified for parameter")) {
      // PostgreSQL の JDBC ドライバは、#{} 以外の ? もパラメータとして扱う。本番でも同じエラーになる
      return "\n※ SQL に #{} 以外の ?（?| や ?& などの演算子）があると、JDBC ドライバがパラメータとみなします。"
          + "演算子は ?? と書いてください（本番でも同じエラーになります）";
    }
    return "";
  }

  private static String rollback(Connection c) {
    try {
      c.rollback();
      return "（ロールバックしました）";
    } catch (SQLException e) {
      return "（ロールバックに失敗しました。接続を閉じるため、変更は確定しません: " + e.getMessage() + "）";
    }
  }

  private static ResultTable read(ResultSet rs, int maxRows) throws SQLException {
    ResultTable table = new ResultTable();
    ResultSetMetaData meta = rs.getMetaData();
    int n = meta.getColumnCount();
    for (int i = 1; i <= n; i++) {
      table.columns.add(meta.getColumnLabel(i));
      table.columnTypes.add(meta.getColumnTypeName(i));
    }
    while (rs.next()) {
      if (table.rows.size() >= maxRows) {
        table.truncated = true;
        break;
      }
      List<String> row = new ArrayList<>(n);
      for (int i = 1; i <= n; i++) {
        row.add(rs.getString(i));
      }
      table.rows.add(row);
    }
    return table;
  }
}
