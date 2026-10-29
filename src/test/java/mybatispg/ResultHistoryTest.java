package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ResultHistoryTest {

  private static JsonObject entry(String statement, int rows, String error) {
    JsonObject o = new JsonObject();
    o.addProperty("kind", "execute");
    o.addProperty("statementId", statement);
    o.addProperty("executedAt", 1L);
    JsonArray sets = new JsonArray();
    JsonObject rs = new JsonObject();
    JsonArray rowArr = new JsonArray();
    for (int i = 0; i < rows; i++) {
      rowArr.add(JsonParser.parseString("[\"" + i + "\"]"));
    }
    rs.add("rows", rowArr);
    sets.add(rs);
    o.add("resultSets", sets);
    o.addProperty("error", error);
    return o;
  }

  @Test
  void 新しいものから50件を残し_一覧は新しい順_中身を取り出せる(@TempDir Path dir) throws Exception {
    ResultHistory h = ResultHistory.open(dir, "proj");
    String first = h.add(entry("m.first", 1, null));
    String last = null;
    for (int i = 0; i < ResultHistory.KEEP; i++) {
      last = h.add(entry("m.s" + i, i % 3, i == 49 ? "ERROR: 失敗\n詳しく" : null));
    }
    List<Map<String, Object>> list = h.list();
    assertEquals(ResultHistory.KEEP, list.size());
    assertEquals(last, list.get(0).get("id"));
    assertEquals("m.s49", list.get(0).get("statementId"));
    assertEquals("ERROR: 失敗", list.get(0).get("error"));
    assertEquals(1, list.get(0).get("rows"));
    // 一番古いもの（m.first）は消えている
    assertNull(h.get(first));
    assertEquals("m.s49", h.get(last).get("statementId").getAsString());
    // 自分だけが読める
    Path file = Files.list(dir).filter(Files::isDirectory).findFirst().get().resolve(last + ".json");
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    h.clear();
    assertTrue(h.list().isEmpty());
  }
}
