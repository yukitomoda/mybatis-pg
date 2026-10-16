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
  }

  public static final class ExplainResult {
    /** EXPLAIN を付ける前の ? 付き SQL */
    public final String sql;
    /** EXPLAIN (FORMAT JSON) の結果をそのまま */
    public final String plan;
    public final List<String> warnings = new ArrayList<>();
    public long elapsedMillis;

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
