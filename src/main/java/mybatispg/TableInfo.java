package mybatispg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表の情報（行数、サイズ、統計の更新日時、インデックス）を読む。計画の分析の手がかりにする。
 * 読み取り専用のトランザクションで読み、最後にロールバックする。
 */
public final class TableInfo {

  private static final String TABLE_SQL = "SELECT c.oid, n.nspname, c.relname, c.reltuples::bigint AS estimated_rows,"
      + " pg_total_relation_size(c.oid) AS total_bytes, pg_relation_size(c.oid) AS table_bytes,"
      + " s.n_live_tup, s.n_dead_tup, s.last_analyze, s.last_autoanalyze, s.last_vacuum, s.last_autovacuum,"
      + " s.seq_scan, s.idx_scan"
      + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
      + " LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid"
      // stringtype=unspecified の接続でも型が決まるよう、パラメータの型を明示する
      + " WHERE c.relkind IN ('r', 'p', 'm') AND c.relname = ?::name"
      + " AND (?::text IS NULL AND pg_table_is_visible(c.oid) OR n.nspname = ?::name)"
      + " ORDER BY n.nspname";

  private static final String INDEX_SQL = "SELECT i.relname, pg_get_indexdef(i.oid) AS definition,"
      + " pg_relation_size(i.oid) AS bytes, si.idx_scan, x.indisunique, x.indisprimary, x.indisvalid"
      + " FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid"
      + " LEFT JOIN pg_stat_user_indexes si ON si.indexrelid = i.oid"
      + " WHERE x.indrelid = ?::oid ORDER BY x.indisprimary DESC, i.relname";

  private TableInfo() {
  }

  /**
   * @param names 表の名前（schema.table も可）。見つからない名前は notFound に入れる
   */
  public static Map<String, Object> read(QueryExecutor.ConnectionFactory connections, List<String> names)
      throws QueryExecutor.ExecutionException {
    Connection c;
    try {
      c = connections.open();
    } catch (SQLException | RuntimeException e) {
      throw new QueryExecutor.ExecutionException("接続できません\n" + Errors.describe(e), e);
    }
    try {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SET TRANSACTION READ ONLY");
      }
      List<Map<String, Object>> tables = new ArrayList<>();
      List<String> notFound = new ArrayList<>();
      for (String name : names) {
        int dot = name.indexOf('.');
        String schema = dot < 0 ? null : name.substring(0, dot);
        String table = dot < 0 ? name : name.substring(dot + 1);
        List<Map<String, Object>> found = readTable(c, schema, table);
        if (found.isEmpty()) {
          notFound.add(name);
        }
        tables.addAll(found);
      }
      c.rollback();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("tables", tables);
      body.put("notFound", notFound);
      return body;
    } catch (SQLException | RuntimeException e) {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // 読むだけなので、ロールバックできなくても変更は残らない
      }
      throw new QueryExecutor.ExecutionException(Errors.describe(e), e);
    } finally {
      try {
        c.close();
      } catch (SQLException ignored) {
        // 閉じられなくても結果は変わらない
      }
    }
  }

  private static List<Map<String, Object>> readTable(Connection c, String schema, String table) throws SQLException {
    List<Map<String, Object>> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(TABLE_SQL)) {
      ps.setString(1, table);
      ps.setString(2, schema);
      ps.setString(3, schema);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> t = new LinkedHashMap<>();
          t.put("schema", rs.getString("nspname"));
          t.put("name", rs.getString("relname"));
          t.put("estimatedRows", rs.getLong("estimated_rows"));
          t.put("totalBytes", rs.getLong("total_bytes"));
          t.put("tableBytes", rs.getLong("table_bytes"));
          t.put("liveRows", nullableLong(rs, "n_live_tup"));
          t.put("deadRows", nullableLong(rs, "n_dead_tup"));
          t.put("lastAnalyze", latest(rs.getTimestamp("last_analyze"), rs.getTimestamp("last_autoanalyze")));
          t.put("lastVacuum", latest(rs.getTimestamp("last_vacuum"), rs.getTimestamp("last_autovacuum")));
          t.put("seqScans", nullableLong(rs, "seq_scan"));
          t.put("indexScans", nullableLong(rs, "idx_scan"));
          t.put("indexes", readIndexes(c, rs.getLong("oid")));
          out.add(t);
        }
      }
    }
    return out;
  }

  private static List<Map<String, Object>> readIndexes(Connection c, long tableOid) throws SQLException {
    List<Map<String, Object>> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(INDEX_SQL)) {
      ps.setLong(1, tableOid);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> i = new LinkedHashMap<>();
          i.put("name", rs.getString("relname"));
          i.put("definition", rs.getString("definition"));
          i.put("bytes", rs.getLong("bytes"));
          i.put("scans", nullableLong(rs, "idx_scan"));
          i.put("unique", rs.getBoolean("indisunique"));
          i.put("primary", rs.getBoolean("indisprimary"));
          i.put("valid", rs.getBoolean("indisvalid"));
          out.add(i);
        }
      }
    }
    return out;
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }

  /** 新しいほうの日時（ISO 形式）。どちらもなければ null */
  private static String latest(Timestamp a, Timestamp b) {
    Timestamp t = a == null ? b : b == null ? a : a.after(b) ? a : b;
    return t == null ? null : t.toInstant().toString();
  }
}
