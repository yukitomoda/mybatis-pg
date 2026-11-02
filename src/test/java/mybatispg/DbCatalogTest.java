package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbCatalogTest {

  private static final String URL = System.getenv("MYBATIS_PG_TEST_URL");

  @Test
  void 出すスキーマの表とコメントだけを読み_コメントを覚えておく(@TempDir Path dir) throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA IF EXISTS cattest_a CASCADE");
      s.execute("DROP SCHEMA IF EXISTS cattest_b CASCADE");
      s.execute("CREATE SCHEMA cattest_a");
      s.execute("CREATE SCHEMA cattest_b");
      s.execute("CREATE TABLE cattest_a.users (id int PRIMARY KEY)");
      s.execute("CREATE TABLE cattest_b.logs (id int PRIMARY KEY)");
      s.execute("COMMENT ON TABLE cattest_a.users IS '利用者'");
      s.execute("COMMENT ON COLUMN cattest_a.users.id IS '利用者 ID'");
      s.execute("COMMENT ON TABLE cattest_b.logs IS 'ログ'");
      try {
        assertEquals(Collections.singletonList("cattest_a.users"), DbCatalog.tables(c, Collections.singletonList("cattest_a")));
        DbCatalog.Comments cm = DbCatalog.readComments(c, Collections.singletonList("cattest_a"));
        assertEquals("利用者", cm.tables.get("cattest_a.users"));
        assertEquals("利用者 ID", cm.columns.get("cattest_a.users.id"));
        assertNull(cm.tables.get("cattest_b.logs"));
        // スキーマの一覧には、表の数が出る
        assertTrue(DbCatalog.schemas(c).stream().anyMatch(m -> "cattest_b".equals(m.get("name")) && Long.valueOf(1).equals(m.get("tables"))));

        // 覚えておく（ファイルにも残る）。出すスキーマが変わったら使わない
        new DbCatalog(dir).put("conn", cm);
        DbCatalog again = new DbCatalog(dir);
        assertNotNull(again.cached("conn", Collections.singletonList("cattest_a")));
        assertEquals("利用者", again.cached("conn", Collections.singletonList("cattest_a")).tables.get("cattest_a.users"));
        assertNull(again.cached("conn", Arrays.asList("cattest_a", "cattest_b")));
        assertNull(again.cached("conn", null));
        assertNull(again.cached("other", Collections.singletonList("cattest_a")));
      } finally {
        s.execute("DROP SCHEMA cattest_a CASCADE");
        s.execute("DROP SCHEMA cattest_b CASCADE");
      }
    }
  }
}
