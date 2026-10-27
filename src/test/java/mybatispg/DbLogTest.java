package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbLogTest {

  private static final String URL = System.getenv("MYBATIS_PG_TEST_URL");

  @Test
  void 古い日のファイルを消す(@TempDir Path dir) throws Exception {
    LocalDate today = LocalDate.of(2026, 10, 27);
    for (String name : new String[] {"db-2026-10-27.log", "db-2026-10-21.log", "db-2026-10-20.log", "db-2025-01-01.log", "other.log"}) {
      Files.write(dir.resolve(name), new byte[0]);
    }
    // 7 日（10/21〜10/27）を残す
    assertEquals(2, DbLog.deleteOld(dir, today, 7));
    assertTrue(Files.exists(dir.resolve("db-2026-10-27.log")));
    assertTrue(Files.exists(dir.resolve("db-2026-10-21.log")));
    assertTrue(Files.exists(dir.resolve("other.log")));
    assertTrue(!Files.exists(dir.resolve("db-2026-10-20.log")));
  }

  @Test
  void SQLと値と時間とエラーを書く(@TempDir Path dir) throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    DbLog.start(dir, 7);
    try (Connection c = DbLog.wrap(DriverManager.getConnection(URL), "test", 5)) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SELECT 1\n   AS one");
      }
      try (PreparedStatement ps = c.prepareStatement("SELECT ?::int + ?::int")) {
        ps.setInt(1, 2);
        ps.setString(2, "3");
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals(5, rs.getInt(1));
        }
      }
      try (Statement s = c.createStatement()) {
        assertThrows(SQLException.class, () -> s.executeQuery("SELECT * FROM no_such_table_for_log"));
      }
      c.rollback();
    } finally {
      DbLog.event("test", "おわり");
      DbLog.stop();
    }
    String log = new String(Files.readAllBytes(dir.resolve("db-" + LocalDate.now() + ".log")), StandardCharsets.UTF_8);
    assertTrue(log.matches("(?s).*\\[#\\d+ test\\] connect 5ms\n.*"), log);
    assertTrue(log.contains("setAutoCommit false"), log);
    // 改行は 1 つの空白にする
    assertTrue(log.matches("(?s).*\\[#\\d+ test\\] execute \\d+ms SELECT 1 AS one\n.*"), log);
    assertTrue(log.matches("(?s).*query \\d+ms SELECT \\?::int \\+ \\?::int \\| 値: \\[2, 3\\]\n.*"), log);
    assertTrue(log.matches("(?s).*query \\d+ms エラー \\(42P01\\) .*no_such_table_for_log.*"), log);
    assertTrue(log.matches("(?s).*\\] rollback \\d+ms\n.*"), log);
    assertTrue(log.matches("(?s).*\\] close \\d+ms\n.*"), log);
    assertTrue(log.contains("[test] おわり"), log);
  }
}
