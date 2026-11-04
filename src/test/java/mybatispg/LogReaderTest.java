package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogReaderTest {

  private static String line(String t, String src, String kind, String extra) {
    return "{\"t\":\"" + t + "\",\"src\":\"" + src + "\",\"kind\":\"" + kind + "\"" + extra + "}\n";
  }

  @SuppressWarnings("unchecked")
  private static List<String> kinds(Map<String, Object> page) {
    List<String> out = new ArrayList<>();
    for (JsonObject e : (List<JsonObject>) page.get("entries")) {
      out.add(e.get("t").getAsString().substring(11, 19) + " " + e.get("kind").getAsString());
    }
    return out;
  }

  @Test
  void 新しい順に読み_絞り込みと続きの位置と読む行数の上限を守る(@TempDir Path dir) throws Exception {
    // 前の日はテキストのログ（前の版）、今日は JSON Lines
    Files.write(dir.resolve("db-2026-11-03.log"), ("2026-11-03 09:00:00.000 [#1 x] connect 3ms\n"
        + "2026-11-03 09:00:01.000 [#1 x] query 1ms エラー (42P01) relation does not exist | SELECT 1\n").getBytes(StandardCharsets.UTF_8));
    StringBuilder today = new StringBuilder();
    today.append(line("2026-11-04 10:00:00.000", "画面", "connect", ",\"ms\":2"));
    today.append(line("2026-11-04 10:00:01.000", "解析", "execute", ",\"sql\":\"PREPARE p AS SELECT 1\""));
    today.append(line("2026-11-04 10:00:02.000", "解析", "execute", ",\"sql\":\"EXPLAIN x\",\"err\":\"syntax error\""));
    today.append(line("2026-11-04 10:00:03.000", "画面", "query", ",\"sql\":\"SELECT * FROM users\""));
    today.append(line("2026-11-04 10:00:04.000", "画面", "api", ",\"path\":\"/api/render\",\"err\":\"SQL を組み立てられません\""));
    Files.write(dir.resolve("db-2026-11-04.jsonl"), today.toString().getBytes(StandardCharsets.UTF_8));

    LogReader.Query all = new LogReader.Query();
    all.includeAnalysis = true;
    Map<String, Object> page = LogReader.read(dir, null, all, 100, 1000);
    assertEquals(java.util.Arrays.asList("10:00:04 api", "10:00:03 query", "10:00:02 execute", "10:00:01 execute", "10:00:00 connect",
        "09:00:01 text", "09:00:00 text"), kinds(page));
    assertNull(page.get("next"));

    // 解析を除く（既定）
    LogReader.Query screen = new LogReader.Query();
    assertEquals(java.util.Arrays.asList("10:00:04 api", "10:00:03 query", "10:00:00 connect", "09:00:01 text", "09:00:00 text"),
        kinds(LogReader.read(dir, null, screen, 100, 1000)));

    // エラーだけ（前の版のテキストのログの「エラー」も）
    LogReader.Query errors = new LogReader.Query();
    errors.errorsOnly = true;
    errors.includeAnalysis = true;
    assertEquals(java.util.Arrays.asList("10:00:04 api", "10:00:02 execute", "09:00:01 text"), kinds(LogReader.read(dir, null, errors, 100, 1000)));

    // 文字で絞る（大文字・小文字を区別しない）
    LogReader.Query text = new LogReader.Query();
    text.text = "users";
    assertEquals(java.util.Collections.singletonList("10:00:03 query"), kinds(LogReader.read(dir, null, text, 100, 1000)));

    // 2 件ずつ読み、続きの位置から読む（ファイルをまたぐ）
    Map<String, Object> p1 = LogReader.read(dir, null, all, 2, 1000);
    assertEquals(java.util.Arrays.asList("10:00:04 api", "10:00:03 query"), kinds(p1));
    Map<String, Object> p2 = LogReader.read(dir, (String) p1.get("next"), all, 4, 1000);
    assertEquals(java.util.Arrays.asList("10:00:02 execute", "10:00:01 execute", "10:00:00 connect", "09:00:01 text"), kinds(p2));
    Map<String, Object> p3 = LogReader.read(dir, (String) p2.get("next"), all, 4, 1000);
    assertEquals(java.util.Collections.singletonList("09:00:00 text"), kinds(p3));

    // 読む行数の上限で止め、続きの位置を知らせる
    Map<String, Object> capped = LogReader.read(dir, null, errors, 100, 2);
    assertEquals(java.util.Collections.singletonList("10:00:04 api"), kinds(capped));
    assertEquals(true, capped.get("capped"));
    assertTrue(((String) capped.get("next")).startsWith("db-2026-11-04.jsonl:"));

    // id の行から読む（その行より 1 バイト後ろを位置にする）
    @SuppressWarnings("unchecked")
    String id = ((List<JsonObject>) page.get("entries")).get(2).get("id").getAsString();
    String at = id.substring(0, id.lastIndexOf(':') + 1) + (Long.parseLong(id.substring(id.lastIndexOf(':') + 1)) + 1);
    assertEquals("10:00:02 execute", kinds(LogReader.read(dir, at, all, 1, 1000)).get(0));
  }
}
