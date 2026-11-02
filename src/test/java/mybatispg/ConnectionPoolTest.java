package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {

  private static final String URL = System.getenv("MYBATIS_PG_TEST_URL");

  private static String one(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  @Test
  void 使い終わった接続を片付けて使い回す() throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    ConnectionPool pool = new ConnectionPool();
    String pid;
    String defaultPath;
    try (Connection c = pool.borrow("k", () -> DriverManager.getConnection(URL))) {
      pid = one(c, "SELECT pg_backend_pid()");
      defaultPath = one(c, "SHOW search_path");
      // セッションに状態を残す: 設定・PREPARE・一時表・読み取り専用・開いたままのトランザクション
      try (Statement s = c.createStatement()) {
        s.execute("SET search_path = pg_catalog");
        s.execute("PREPARE leftover AS SELECT 1");
        s.execute("CREATE TEMP TABLE leftover_t (id int)");
      }
      // ドライバが内部で名前付きの文にする（同じ文を 5 回以上）
      for (int i = 0; i < 7; i++) {
        try (PreparedStatement ps = c.prepareStatement("SELECT ?::int + 1")) {
          ps.setInt(1, i);
          ps.executeQuery().close();
        }
      }
      c.setReadOnly(true);
      c.setAutoCommit(false);
      one(c, "SELECT 1");
    }
    assertEquals(1, pool.idleCount("k"));
    try (Connection c = pool.borrow("k", () -> {
      throw new SQLException("開き直さないはず");
    })) {
      assertEquals(pid, one(c, "SELECT pg_backend_pid()"));
      assertEquals(defaultPath, one(c, "SHOW search_path"));
      assertEquals("0", one(c, "SELECT count(*) FROM pg_prepared_statements WHERE name = 'leftover'"));
      assertEquals("0", one(c, "SELECT count(*) FROM pg_class WHERE relname = 'leftover_t' AND relpersistence = 't'"));
      assertTrue(c.getAutoCommit());
      assertFalse(c.isReadOnly());
      // DISCARD ALL の後も、ドライバの名前付きの文を使える
      for (int i = 0; i < 7; i++) {
        try (PreparedStatement ps = c.prepareStatement("SELECT ?::int + 1")) {
          ps.setInt(1, i);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(i + 1, rs.getInt(1));
          }
        }
      }
    }
    // 鍵が違えば使い回さない
    try (Connection c = pool.borrow("other", () -> DriverManager.getConnection(URL))) {
      assertNotEquals(pid, one(c, "SELECT pg_backend_pid()"));
    }
  }

  @Test
  void とっておく数に上限があり_古い接続は閉じる() throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    ConnectionPool pool = new ConnectionPool();
    long[] now = {1_000_000L};
    pool.clock = () -> now[0];
    List<Connection> cs = new ArrayList<>();
    for (int i = 0; i < ConnectionPool.MAX_IDLE + 2; i++) {
      cs.add(pool.borrow("k", () -> DriverManager.getConnection(URL)));
    }
    for (Connection c : cs) {
      c.close();
      // 閉じた接続は使えない
      assertTrue(c.isClosed());
    }
    assertEquals(ConnectionPool.MAX_IDLE, pool.idleCount("k"));
    // しばらく使わなければ閉じる
    now[0] += ConnectionPool.IDLE_MILLIS + 1;
    pool.sweep();
    assertEquals(0, pool.idleCount("k"));
    // 接続先を変えたら閉じる
    pool.borrow("conn1\u0000x", () -> DriverManager.getConnection(URL)).close();
    assertEquals(1, pool.idleCount("conn1\u0000x"));
    pool.closeAll("conn1\u0000");
    assertEquals(0, pool.idleCount("conn1\u0000x"));
  }
}
