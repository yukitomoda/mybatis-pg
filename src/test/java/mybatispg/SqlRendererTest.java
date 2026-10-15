package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SqlRendererTest {

  private static SqlRenderer renderer;

  @BeforeAll
  static void setUp() throws Exception {
    Path dir = Paths.get("src/test/resources/mappers");
    // include 先（Common.xml）を後から読み込み、読み込み順に依存しないことも確かめる
    renderer = SqlRenderer.load(Arrays.asList(dir.resolve("UserMapper.xml"), dir.resolve("Common.xml")));
    assertEquals(java.util.Collections.emptyList(), renderer.getLoadErrors());
  }

  @Test
  void statementIds() {
    assertEquals(Arrays.asList("sample.UserMapper.bulkInsert", "sample.UserMapper.findStatic", "sample.UserMapper.search"),
        renderer.statementIds());
  }

  @Test
  void 静的SQLでは文字列やコメント中の疑問符に触れない() {
    Map<String, Object> p = new HashMap<>();
    p.put("id", 10);
    p.put("name", "O'Reilly");

    SqlRenderer.Result r = print("sample.UserMapper.findStatic", p);

    assertEquals("SELECT id, '?' AS q /* ? */ FROM users WHERE id = 10 AND tags ?| array['a', 'b'] -- ? "
        + "AND name = 'O''Reilly'", normalize(r.literalSql));
    assertEquals(Arrays.asList("1: id = 10", "2: name = O'Reilly"), strings(r.bindings));
  }

  @Test
  void 動的SQLでbindとforeachとドル記号の置換を展開する() {
    Map<String, Object> cond = new HashMap<>();
    cond.put("name", "taro");
    cond.put("status", null);
    Map<String, Object> p = new HashMap<>();
    p.put("cond", cond);
    p.put("ids", Arrays.asList(1, 2, 3));
    p.put("deleted", false);
    p.put("orderBy", "id DESC");

    SqlRenderer.Result r = print("sample.UserMapper.search", p);

    assertEquals("SELECT id, name, status FROM users WHERE name LIKE '%taro%' AND id IN (1,2,3) "
        + "AND note = '#?' AND deleted = FALSE ORDER BY id DESC", normalize(r.literalSql));
    assertEquals(Arrays.asList("1: namePattern = %taro%", "2: __frch_id_0 = 1", "3: __frch_id_1 = 2",
        "4: __frch_id_2 = 3", "5: deleted = false"), strings(r.bindings));
  }

  @Test
  void foreachの要素がMapでもプロパティを解決する() {
    List<Map<String, Object>> users = new ArrayList<>();
    for (int i = 1; i <= 2; i++) {
      Map<String, Object> u = new HashMap<>();
      u.put("id", i);
      u.put("name", i == 1 ? "a" : null);
      users.add(u);
    }
    Map<String, Object> p = new HashMap<>();
    p.put("users", users);

    SqlRenderer.Result r = print("sample.UserMapper.bulkInsert", p);

    assertEquals("INSERT INTO users (id, name) VALUES (1, 'a') , (2, NULL)", normalize(r.literalSql));
  }

  private static SqlRenderer.Result print(String id, Object param) {
    SqlRenderer.Result r = renderer.render(id, param);
    System.out.println("=== " + id);
    System.out.println("[? 付き SQL]\n" + r.placeholderSql.trim());
    System.out.println("[バインド値]");
    for (SqlRenderer.Binding b : r.bindings) {
      System.out.println("  " + b);
    }
    System.out.println("[値埋め込み SQL]\n" + r.literalSql.trim() + "\n");
    return r;
  }

  private static String normalize(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private static List<String> strings(List<?> list) {
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      out.add(o.toString());
    }
    return out;
  }
}
