package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryExecutorTest {

  private static final SqlRenderer RENDERER =
      SqlRenderer.load(Collections.singletonList(Paths.get("src/test/resources/exec/ExecMapper.xml")));

  private String url;
  /** 実行のたびに開いた接続を数える */
  private int opened;

  @BeforeEach
  void createDatabase() throws Exception {
    url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
      s.execute("INSERT INTO items VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    }
  }

  private QueryExecutor.Result run(String id, Map<String, Object> params, QueryExecutor.Options options)
      throws Exception {
    return QueryExecutor.execute(RENDERER, "exec.ItemMapper." + id, params, () -> {
      opened++;
      return DriverManager.getConnection(url);
    }, options);
  }

  private int count() throws Exception {
    try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM items")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static Map<String, Object> params(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void 検索結果を文字列で返す() throws Exception {
    QueryExecutor.Result r = run("find", params("ids", Arrays.asList(1L, 3L)), new QueryExecutor.Options());
    assertEquals(1, r.resultSets.size());
    QueryExecutor.ResultTable t = r.resultSets.get(0);
    assertEquals(Arrays.asList("ID", "NAME"), t.columns);
    assertEquals(Arrays.asList(Arrays.asList("1", "a"), Arrays.asList("3", "c")), t.rows);
    assertFalse(t.truncated);
    assertTrue(r.sql.contains("id IN (?,?)"), r.sql);
  }

  @Test
  void 上限を超える行は切り捨てて印を付ける() throws Exception {
    QueryExecutor.Options options = new QueryExecutor.Options();
    options.maxRows = 2;
    QueryExecutor.ResultTable t = run("find", params(), options).resultSets.get(0);
    assertEquals(2, t.rows.size());
    assertTrue(t.truncated);
  }

  @Test
  void 既定ではロールバックする() throws Exception {
    QueryExecutor.Result r = run("insert", params("id", 10L, "name", "x"), new QueryExecutor.Options());
    assertEquals(Collections.singletonList(1), r.updateCounts);
    assertFalse(r.committed);
    assertEquals(3, count());
  }

  @Test
  void commitを指定するとコミットする() throws Exception {
    QueryExecutor.Options options = new QueryExecutor.Options();
    options.commit = true;
    QueryExecutor.Result r = run("insert", params("id", 10L, "name", "x"), options);
    assertTrue(r.committed);
    assertEquals(4, count());
  }

  @Test
  void SQLのエラーはロールバックして伝える() throws Exception {
    QueryExecutor.Options options = new QueryExecutor.Options();
    options.commit = true;
    QueryExecutor.ExecutionException e = assertThrows(QueryExecutor.ExecutionException.class,
        () -> run("broken", params("name", "x"), options));
    assertTrue(e.getMessage().contains("NO_SUCH_TABLE"), e.getMessage());
    assertTrue(e.getMessage().contains("ロールバックしました"), e.getMessage());
  }

  @Test
  void 接続できなければその旨を伝える() {
    QueryExecutor.ExecutionException e = assertThrows(QueryExecutor.ExecutionException.class,
        () -> QueryExecutor.execute(RENDERER, "exec.ItemMapper.find", params(), () -> {
          throw new java.sql.SQLException("connection refused");
        }, new QueryExecutor.Options()));
    assertTrue(e.getMessage().startsWith("接続できません"), e.getMessage());
  }

  @Test
  void 式の評価エラーは接続する前に失敗する() {
    // ids に数値を渡すと foreach が評価できない
    assertThrows(RuntimeException.class, () -> run("find", params("ids", 1L), new QueryExecutor.Options()));
    assertEquals(0, opened);
  }

  @Test
  void selectKeyは実行しないことを警告する() throws Exception {
    QueryExecutor.Result r = run("insertWithKey", params("id", 20L, "name", "y"), new QueryExecutor.Options());
    assertTrue(r.warnings.get(0).contains("selectKey"), r.warnings.toString());
    assertEquals(Collections.singletonList(1), r.updateCounts);
  }

  @Test
  void 続きは読み飛ばして次の分を取り_件数も数える() throws Exception {
    String pg = System.getenv("MYBATIS_PG_TEST_URL");
    org.junit.jupiter.api.Assumptions.assumeTrue(pg != null, "MYBATIS_PG_TEST_URL がない");
    String sql = "SELECT g AS n FROM generate_series(1, ?) g ORDER BY g";
    QueryExecutor.ParameterSetter p = ps -> ps.setInt(1, 25);
    QueryExecutor.PartResult first = QueryExecutor.fetch(sql, p, () -> DriverManager.getConnection(pg), 0, 0, 10, null, 30);
    assertEquals(Arrays.asList("1"), first.table.rows.get(0));
    assertTrue(first.table.truncated);
    QueryExecutor.PartResult next = QueryExecutor.fetch(sql, p, () -> DriverManager.getConnection(pg), 0, 20, 10, null, 30);
    assertEquals(5, next.table.rows.size());
    assertEquals(Arrays.asList("21"), next.table.rows.get(0));
    assertFalse(next.table.truncated);
    assertEquals(null, next.total);
    // 件数を数える（切り捨てたときだけ数える）
    QueryExecutor.PartResult counted = QueryExecutor.fetch("SELECT * FROM (\n" + sql + "\n) AS part", p,
        () -> DriverManager.getConnection(pg), 0, 0, 10, "SELECT count(*) FROM (\n" + sql + "\n) AS part", 30);
    assertEquals(Long.valueOf(25), counted.total);
    // 読み取り専用で流すので、更新する文はできない
    QueryExecutor.ExecutionException e = assertThrows(QueryExecutor.ExecutionException.class, () -> QueryExecutor.fetch(
        "CREATE TEMP TABLE t AS SELECT 1", null, () -> DriverManager.getConnection(pg), 0, 0, 10, null, 30));
    assertTrue(e.getMessage().startsWith("続き・再取得は読み取り専用で流すので"), e.getMessage());
  }
}
