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
    for (String name : new String[] {"db-2026-10-27.log", "db-2026-10-21.jsonl", "db-2026-10-20.log", "db-2025-01-01.jsonl", "other.log"}) {
      Files.write(dir.resolve(name), new byte[0]);
    }
    // 7 日（10/21〜10/27）を残す
    assertEquals(2, DbLog.deleteOld(dir, today, 7));
    assertTrue(Files.exists(dir.resolve("db-2026-10-27.log")));
    assertTrue(Files.exists(dir.resolve("db-2026-10-21.jsonl")));
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
    java.util.List<com.google.gson.JsonObject> lines = new java.util.ArrayList<>();
    for (String l : Files.readAllLines(dir.resolve("db-" + LocalDate.now() + ".jsonl"), StandardCharsets.UTF_8)) {
      lines.add(com.google.gson.JsonParser.parseString(l).getAsJsonObject());
    }
    String all = lines.toString();
    com.google.gson.JsonObject connect = lines.get(0);
    assertEquals("connect", connect.get("kind").getAsString());
    assertEquals("test", connect.get("conn").getAsString());
    assertEquals(5, connect.get("ms").getAsInt());
    assertEquals("画面", connect.get("src").getAsString());
    assertTrue(lines.stream().anyMatch(e -> e.get("kind").getAsString().equals("setAutoCommit false")), all);
    // 改行は 1 つの空白にする
    assertTrue(lines.stream().anyMatch(e -> e.get("kind").getAsString().equals("execute") && e.get("sql").getAsString().equals("SELECT 1 AS one")), all);
    assertTrue(lines.stream().anyMatch(e -> e.get("kind").getAsString().equals("query") && e.has("values")
        && e.get("values").toString().equals("[\"2\",\"3\"]")), all);
    assertTrue(lines.stream().anyMatch(e -> e.has("err") && e.get("err").getAsString().contains("no_such_table_for_log")
        && e.get("state").getAsString().equals("42P01")), all);
    assertTrue(lines.stream().anyMatch(e -> e.get("kind").getAsString().equals("rollback")), all);
    assertTrue(lines.stream().anyMatch(e -> e.get("kind").getAsString().equals("close")), all);
    assertTrue(lines.stream().anyMatch(e -> e.get("kind").getAsString().equals("event") && e.get("msg").getAsString().equals("おわり")), all);
  }
}
